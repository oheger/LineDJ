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

package de.oliver_heger.linedj.archive.metadata.persistence

import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetadataWriterActor.{MediumData, MetadataWritten, ProcessMedium, StreamOperationComplete}
import de.oliver_heger.linedj.io.stream.ListSeparatorStage
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.{GetMetadata, MediaMetadata, MetadataResponse}
import org.apache.pekko.actor.{Actor, ActorContext, ActorLogging, ActorRef}
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.stream.IOResult
import org.apache.pekko.stream.scaladsl.{FileIO, Source}

import java.nio.file.{Path, StandardOpenOption}
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object PersistentMetadataWriterActor:

  /**
    * A message processed by [[PersistentMetadataWriterActor]] that tells it to
    * write metadata files for a specific medium. This message causes the
    * actor to register for this medium and to handle notifications about
    * chunks of metadata.
    *
    * @param mediumID         the ID of the affected medium
    * @param target           the path to the metadata file to be written
    * @param metadataManager  the metadata manager actor
    * @param resolvedSize     the number of resolved files on this medium
    */
  case class ProcessMedium(mediumID: MediumID, target: Path, metadataManager: ActorRef, resolvedSize: Int)

  /**
    * An internally used message that is passed to this actor when a stream
    * operation has finished. Only a single stream operation should be done at
    * any given time. Therefore, when an operation is finished a notification
    * has to be sent to trigger the next operation if one is pending.
    */
  private[persistence] case object StreamOperationComplete

  /**
    * A case class storing information about a medium to be processed. This
    * class keeps track about the number of elements that have already been
    * written; so it can be determined when the file for this medium has to be
    * written again.
    *
    * @param process         the ''ProcessMedium'' message for this medium
    * @param elementsWritten the number of elements that have been written
    * @param elements        the elements recorded for this medium
    * @param trigger         the actor that was the sender of this process
    *                        message; it is to be notified on write operations
    */
  private[persistence] case class MediumData(process: ProcessMedium, elementsWritten: Int,
                                             elements: Map[String, MediaMetadata], trigger: ActorRef)

  /**
    * A message sent by [[PersistentMetadataWriterActor]] to the triggering
    * actor after each write operation for metadata. This is received by the
    * metadata reader actor, so that it can update its record of existing 
    * metadata files.
    *
    * @param process the ''ProcessMedium'' message related to the write
    *                operation
    * @param success a flag whether the operation was successful
    */
  private[persistence] case class MetadataWritten(process: ProcessMedium, success: Boolean)


/**
  * An actor that produces metadata files for media while they are processed
  * by the metadata manager.
  *
  * This actor comes into play when media are detected for which no or
  * incomplete metadata files exist. In this case, the files on the medium are
  * scanned to extract metadata. When this is done (or if a sufficient number
  * of files has been processed) the obtained metadata should be written out.
  *
  * This actor is notified about media that fall under this category. It
  * registers itself as listener for these media and handles update
  * notifications about incoming metadata. When a configurable number of files
  * on a medium has been processed the metadata file for this medium is
  * written to disk.
  *
  * @param blockSize the number of metadata files processed on a medium
  *                  before the metadata file is written
  */
class PersistentMetadataWriterActor(blockSize: Int,
                                    private[persistence] val resultHandler:
                                    FutureIOResultHandler) extends Actor with ActorLogging:
  def this(blockSize: Int) = this(blockSize, new FutureIOResultHandler)

  /** The JSON converter for metadata. */
  private val metadataConverter = new MetadataJsonConverter

  /**
    * Stores information about media which are currently processed.
    */
  private var mediaInProgress = Map.empty[MediumID, MediumData]

  /**
    * A map that stores the media and corresponding data that are pending to be
    * written to disk.
    */
  private var mediaToBeWritten = Map.empty[MediumID, MediumData]

  /**
    * Flag whether currently a write operation is in progress. This is used to
    * allow only a single write operation at any time.
    */
  private var writeInProgress = false

  override def receive: Receive =
    case p: ProcessMedium =>
      p.metadataManager ! GetMetadata(p.mediumID, registerAsListener = true, 0)
      mediaInProgress += p.mediumID -> MediumData(p, p.resolvedSize, Map.empty, sender())

    case MetadataResponse(c, _) =>
      mediaInProgress.get(c.mediumID).foreach { mediumData =>
        val nextElements = mediumData.elements ++ c.data
        val nextData = if nextElements.size - mediumData.elementsWritten >= blockSize || c
          .complete then
          val d = mediumData.copy(elementsWritten = nextElements.size, elements = nextElements)
          mediaToBeWritten += c.mediumID -> d
          startWriteOperationIfPossible()
          d
        else mediumData.copy(elements = nextElements)

        if c.complete then mediaInProgress -= c.mediumID
        else mediaInProgress += (c.mediumID -> nextData)
      }

    case StreamOperationComplete =>
      writeInProgress = false
      startWriteOperationIfPossible()

  /**
    * Checks if a write operation can be started. If so, the write is
    * triggered.
    */
  private def startWriteOperationIfPossible(): Unit =
    if !writeInProgress && mediaToBeWritten.nonEmpty then
      val mid = mediaToBeWritten.keys.head
      val mediumData = mediaToBeWritten(mid)
      val result = triggerWriteMetadataFile(mediumData)
      resultHandler.handleFutureResult(context, result, self, log, mediumData)
      writeInProgress = true
      mediaToBeWritten -= mid

  /**
    * Triggers writing of a file with metadata. The specified map with 
    * metadata is written into the target file for the given medium.
    *
    * @param mediumData the object with data about the medium in question
    * @return a future for the result of the write operation
    */
  private def triggerWriteMetadataFile(mediumData: MediumData): Future[IOResult] =
    import context.system
    log.info("Writing metadata file {}.", mediumData.process.target)
    val source = Source(mediumData.elements)
    val listStage =
      new ListSeparatorStage[(String, MediaMetadata)]("[\n", ",\n", "\n]\n")((e, _) =>
        processElement(e._1, e._2))
    source.via(listStage)
      .runWith(FileIO.toPath(mediumData.process.target,
        Set(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING)))

  /**
    * Processes a single media file in the stream for writing media files.
    * The element is an entry of the map from a metadata chunk. It is
    * converted to a JSON representation in binary form.
    *
    * @param uri  the URI of the song
    * @param data the metadata for song
    * @return a JSON representation for this song
    */
  private def processElement(uri: String, data: MediaMetadata): String =
    metadataConverter.convert(uri, data)

/**
  * An internally used helper class that handles the future with the IO result
  * produced by a stream operation.
  *
  * The main purpose of this class is to notify the owning actor when writing
  * of the stream is complete. Then the next write operation can be triggered.
  */
private class FutureIOResultHandler:
  /**
    * Handles the specified future IO result.
    *
    * @param context      the actor context
    * @param futureResult the future IO result
    * @param actor        the owning actor
    * @param log          the logger for producing log output
    * @param data         the data object for the write operation
    */
  def handleFutureResult(context: ActorContext, futureResult: Future[IOResult],
                         actor: ActorRef, log: LoggingAdapter,
                         data: MediumData): Unit =
    import context._
    futureResult onComplete { r =>
      onResultComplete(r, actor, log, data)
    }

  /**
    * Callback method invoked when the future result completes. The method is
    * invoked with one of the involved ''Try'' objects: Either the
    * ''Try[IOResult]'' or the ''Try[Done]'' from the ''IOResult''. There is
    * need to distinguish here because we are only interested in failures.
    *
    * @param result the completed result
    * @param actor  the owning actor
    * @param log    the logger for producing log output
    * @param data   the data object for the write operation
    */
  protected def onResultComplete(result: Try[_], actor: ActorRef, log: LoggingAdapter,
                                 data: MediumData): Unit =
    actor ! PersistentMetadataWriterActor.StreamOperationComplete
    result match
      case Failure(ex) =>
        log.error(ex, "Stream operation caused an exception!")
        notifyTriggerActor(data, actor, success = false)
      case Success(_) =>
        log.info("Metadata file written successfully.")
        notifyTriggerActor(data, actor, success = true)

  /**
    * Sends a notification message to the trigger actor about the result of the
    * current write operation. That way the trigger actor can keep track on
    * newly written metadata files.
    *
    * @param data    the data object for the write operation
    * @param actor   the owning actor
    * @param success a flag whether the operation was successful
    */
  protected def notifyTriggerActor(data: MediumData, actor: ActorRef, success: Boolean): Unit =
    data.trigger.tell(MetadataWritten(data.process, success), actor)
