/*
 * Copyright 2015-2025 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.oliver_heger.linedj.archiveadmin.validate

import de.oliver_heger.linedj.archiveadmin.validate.MetadataValidator.{MediaFile, Severity}
import de.oliver_heger.linedj.archiveadmin.validate.ValidationModel.{ValidationErrorItem, ValidationFlow}
import de.oliver_heger.linedj.platform.app.ClientApplication
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MetadataService
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer
import net.sf.jguiraffe.gui.builder.window.{Window, WindowEvent, WindowListener, WindowUtils}
import org.apache.logging.log4j.LogManager
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream._
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}

import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object ValidationController:
  /** The prefix for all properties related to metadata validation. */
  val PrefixValidationConfig = "media.validation."

  /**
    * Name of a configuration property that determines the chunk size in which
    * the UI is updated during the validation process when new validation
    * errors are detected. Typically, the UI is not updated for each single
    * error (if there are expected to be many), but only in blocks of this
    * size.
    */
  val PropUIUpdateChunkSize: String = PrefixValidationConfig + "uiUpdateChunkSize"

  /** The default value for the ''PropUIUpdateChunkSize'' property. */
  val DefaultUIUpdateChunkSize = 16

  /**
    * A comparator to sort the table with validation errors. Note that this
    * comparator expects the elements to be compared are of type
    * ''ValidationErrorItem'', though having the generic type ''AnyRef''. This
    * is because the table model has this unspecific element type.
    */
  val ErrorItemComparator: Comparator[AnyRef] = createValidationErrorItemComparator()

  /**
    * Creates a comparator for sorting the table of validation errors.
    *
    * @return the comparator
    */
  private def createValidationErrorItemComparator(): Comparator[AnyRef] =
    (t: scala.Any, t1: scala.Any) => {
      val item1 = t.asInstanceOf[ValidationErrorItem]
      val item2 = t1.asInstanceOf[ValidationErrorItem]
      val r1 = item1.mediumName.compareToIgnoreCase(item2.mediumName)
      if r1 == 0 then
        val r2 = item1.name.compareToIgnoreCase(item2.name)
        if r2 == 0 then
          if item1.severity != item2.severity then
            if item1.severity == Severity.Error then -1
            else 1
          else item1.error.compareToIgnoreCase(item2.error)
        else r2
      else r1
    }

/**
  * A controller class for retrieving and displaying metadata validation
  * results.
  *
  * The controller manages a dialog window that mainly consists of a table
  * view. When the window is opened information about the currently available
  * metadata is fetched, and a stream is started that iterates over all media.
  * The stream uses the injected ''ValidationFlow'' to obtain the results of
  * the validation. These results are added to the table view.
  *
  * @param metaDataService the service for obtaining metadata
  * @param app             the associated client application
  * @param sync            the object to sync with the UI thread
  * @param tableHandler    the table handler component
  * @param validationFlow  the flow that does the actual validation
  * @param converter       the validation item converter
  * @param statusHandler   the handler for the status line
  * @tparam RESULT the result type used by the managed [[ValidationFlow]]
  */
class ValidationController[RESULT](metaDataService: MetadataService[AvailableMedia, Map[MediaFileID, MediaMetadata]],
                                   app: ClientApplication,
                                   sync: GUISynchronizer,
                                   tableHandler: TableHandler,
                                   validationFlow: ValidationFlow[RESULT],
                                   converter: ValidationItemConverter,
                                   statusHandler: StatusLineHandler) extends WindowListener:
  /** The logger. */
  private val log = LogManager.getLogger(getClass)

  /** The EC for running futures. */
  private implicit val ec: ExecutionContext = app.clientApplicationContext.actorSystem.dispatcher

  /** Stores a flag whether errors occurred during validation. */
  private val processingErrors = new AtomicBoolean

  /** The window associated with this controller. */
  private var window: Window = _

  /** The kill switch to cancel the validation stream. */
  private var killSwitch: Option[KillSwitch] = None

  /** A counter for validation results with severity Error. */
  private var validationErrorCount = 0

  /** A flag whether the dialog window has been closed. */
  private var windowClosed = false

  import ValidationController._

  override def windowActivated(windowEvent: WindowEvent): Unit = {}

  /**
    * Notification that the window is closing. If the validation stream is
    * still in progress, it is canceled now.
    *
    * @param windowEvent the window event (ignored)
    */
  override def windowClosing(windowEvent: WindowEvent): Unit =
    windowClosed = true
    killSwitch foreach { ks =>
      log.info("Canceling validation stream.")
      ks.shutdown()
    }

  override def windowClosed(windowEvent: WindowEvent): Unit = {}

  override def windowDeactivated(windowEvent: WindowEvent): Unit = {}

  override def windowDeiconified(windowEvent: WindowEvent): Unit = {}

  override def windowIconified(windowEvent: WindowEvent): Unit = {}

  override def windowOpened(windowEvent: WindowEvent): Unit =
    window = WindowUtils windowFromEvent windowEvent
    startValidationStream()

  /**
    * Closes the associated window. This method is called by the close window
    * action. It will cancel an ongoing validation stream.
    */
  def closeWindow(): Unit =
    window.close(false)

  /**
    * Creates the source for the validation stream. The source emits all the
    * IDs of all media which should be validated.
    *
    * @param media the currently available media
    * @return the source for all media
    */
  private[validate] def createSource(media: AvailableMedia): Source[MediumID, NotUsed] =
    Source(media.mediaList).map(_._1)

  /**
    * Runs the validation stream and returns a kill switch to interrupt it.
    * This method actually starts the stream in which the media validation is
    * performed.
    *
    * @param media the currently available media
    * @return the kill switch to cancel the stream
    */
  private[validate] def materializeValidationStream(media: AvailableMedia): KillSwitch =
    implicit val system: ActorSystem = app.clientApplicationContext.actorSystem
    val updateChunkSize = app.clientApplicationContext.managementConfiguration
      .getInt(PropUIUpdateChunkSize, DefaultUIUpdateChunkSize)
    val source = createSource(media)
    val sink = Sink.foreach[Seq[ValidationErrorItem]](appendValidationErrors)
    val graph = source.viaMat(KillSwitches.single)(Keep.right)
      .mapAsync(1)(mid => metaDataService.fetchMetadataOfMedium(mid)(messageBus).map((mid, _)))
      .map(t => t._2.toList.map(e => MediaFile(t._1, e._1.uri, e._2)))
      .via(validationFlow)
      .filter(_.result.isFailure)
      .mapConcat(converter.generateTableItems(media, _))
      .groupedWithin(updateChunkSize, 10.seconds)
      .toMat(sink)(Keep.both)
    val supervisedGraph = graph.withAttributes(ActorAttributes.supervisionStrategy(createDecider()))
    val (ks, futSink) = supervisedGraph.run()

    futSink foreach (_ => completeStreamProcessing())
    ks

  /**
    * Initiates the stream that validates all media.
    */
  private def startValidationStream(): Unit =
    statusHandler.fetchingMedia()
    metaDataService.fetchMedia()(messageBus) onComplete:
      case Success(media) =>
        doSynced:
          killSwitch = Some(materializeValidationStream(media))

      case Failure(exception) =>
        log.error("Could not retrieve available media!", exception)
        doSynced:
          statusHandler.validationResults(0, 0, successful = false)

  /**
    * Creates the decider for stream supervision. This function returns a
    * decider function which records errors that occurred and then resumes
    * stream execution.
    *
    * @return the decider for stream supervision
    */
  private def createDecider(): Supervision.Decider =
    err =>
      log.error("Error during validation stream processing!", err)
      processingErrors.set(true)
      Supervision.Resume

  /**
    * Appends newly detected validation errors to the table. This method is
    * called from the validation stream when a chunk of errors is available.
    *
    * @param items the validation errors to be appended
    */
  private def appendValidationErrors(items: Seq[ValidationErrorItem]): Unit =
    import scala.jdk.CollectionConverters._
    doSynced:
      val modelSize = tableHandler.getModel.size()
      tableHandler.getModel.addAll(items.asJava)
      tableHandler.rowsInserted(modelSize, modelSize + items.size - 1)

      validationErrorCount += items.count(_.severity == Severity.Error)
      statusHandler.updateProgress(validationErrorCount, validationWarningCount)

  /**
    * Executes some final steps after the validation stream is complete, such
    * as updating the table model. This method is called from a future when the
    * sink of the validation stream completes.
    */
  private def completeStreamProcessing(): Unit = doSynced:
    java.util.Collections.sort(tableHandler.getModel, ErrorItemComparator)
    tableHandler.tableDataChanged()
    statusHandler.validationResults(validationErrorCount, validationWarningCount, !processingErrors.get())
    killSwitch = None

  /**
    * Invokes the given function asynchronously on the event dispatch thread.
    * This function must be used whenever an interaction with the UI is done or
    * state of the controller is updated.
    *
    * @param f the function to execute on the UI thread
    */
  private def doSynced(f: => Unit): Unit =
    sync.asyncInvoke(() => if !windowClosed then f)

  /**
    * Convenience function to calculate the number of validation warnings.
    * This value can be derived from the number of errors.
    *
    * @return the number of validation results with severity Warning
    */
  private def validationWarningCount: Int =
    tableHandler.getModel.size() - validationErrorCount

  /**
    * Convenience function to return the system message bus.
    *
    * @return the message bus
    */
  private def messageBus: MessageBus = app.clientApplicationContext.messageBus
