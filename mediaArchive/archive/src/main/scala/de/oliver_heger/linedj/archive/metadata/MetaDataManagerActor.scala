/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archive.metadata

import java.nio.file.Path

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig.MediaRootData
import de.oliver_heger.linedj.archive.media.{EnhancedMediaScanResult, MediaScanStarts}
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetaDataManagerActor
import de.oliver_heger.linedj.extract.metadata.{MetaDataExtractionActor, ProcessMediaFiles}
import de.oliver_heger.linedj.io.CloseHandlerActor.CloseComplete
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest, CloseSupport, FileData}
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.metadata._
import de.oliver_heger.linedj.shared.archive.union.{MediaContribution, MetaDataProcessingResult}
import de.oliver_heger.linedj.utils.ChildActorFactory

object MetaDataManagerActor {
  /** Default root data used for unknown root paths. */
  private val DefaultMediaRoot = MediaRootData(null, 1, None)

  private class MetaDataManagerActorImpl(config: MediaArchiveConfig, persistenceManager: ActorRef,
                                         metaDataUnionActor: ActorRef)
    extends MetaDataManagerActor(config, persistenceManager, metaDataUnionActor)
      with ChildActorFactory with CloseSupport

  /**
    * Returns creation properties for an actor instance of this type.
    *
    * @param config             the server configuration object
    * @param persistenceManager reference to the persistence manager actor
    * @param metaDataUnionActor reference to the meta data union actor
    * @return creation properties for a new actor instance
    */
  def apply(config: MediaArchiveConfig, persistenceManager: ActorRef,
            metaDataUnionActor: ActorRef): Props =
    Props(classOf[MetaDataManagerActorImpl], config, persistenceManager, metaDataUnionActor)

}

/**
  * The central actor class for managing meta data extraction for a local
  * archive.
  *
  * This actor class coordinates the work of the media manager actor (which
  * scans a directory structure with media files) with the persistence
  * manager actor (which manages already extracted meta data for media files).
  * It receives messages about the media files detected in a folder
  * structure that has been scanned. It matches this data against the meta
  * data read from persistent meta data files. For files for which no meta
  * data is available an extraction process is triggered, so that the
  * persistent meta data can be updated.
  *
  * All meta data - either read from persistent files or extracted manually -
  * is sent to a union meta data manager actor. It is then stored in the union
  * archive an can be queried by clients.
  *
  * @param config             the central configuration object
  * @param persistenceManager reference to the persistence manager actor
  * @param metaDataUnionActor reference to the meta data union actor
  */
class MetaDataManagerActor(config: MediaArchiveConfig, persistenceManager: ActorRef,
                           metaDataUnionActor: ActorRef) extends Actor with ActorLogging {
  this: ChildActorFactory with CloseSupport =>

  import MetaDataManagerActor._

  /** The factory for extractor actors. */
  private val ExtractorFactory = new ExtractorActorFactoryImpl(config)

  /**
    * A map for storing the extracted meta data for all media.
    */
  private var mediaMap = Map.empty[MediumID, MediumDataHandler]

  /** A map with the processor actors for the different media roots. */
  private var processorActors = Map.empty[Path, ActorRef]

  /** A set with IDs for media which have already been completed. */
  private var completedMedia = Set.empty[MediumID]

  /**
    * Stores information about all available media. This is provided by the
    * media manager when the file scan is complete. It is used to determine
    * when the processing of media files is done.
    */
  private var availableMedia: Option[Map[MediumID, MediumInfo]] = None

  /** A flag whether a scan is currently in progress. */
  private var scanInProgress = false

  override def receive: Receive = {
    case MediaScanStarts if !scanInProgress =>
      initiateNewScan()

    case result: MetaDataProcessingResult if !isCloseRequestInProgress =>
      if (handleProcessingResult(result.mediumID, result)) {
        metaDataUnionActor ! result
        checkAndHandleScanComplete()
      }

    case UnresolvedMetaDataFiles(mid, files, result) =>
      val root = result.scanResult.root
      val actorMap = if (processorActors contains root) processorActors
      else processorActors + (root -> createProcessorActor(root, result))
      actorMap(root) ! ProcessMediaFiles(mid, files)
      processorActors = actorMap

    case esr: EnhancedMediaScanResult if scanInProgress && !isCloseRequestInProgress =>
      persistenceManager ! esr
      metaDataUnionActor ! MediaContribution(esr.scanResult.mediaFiles)
      esr.scanResult.mediaFiles foreach prepareHandlerForMedium

    case AvailableMedia(media) =>
      availableMedia = Some(media)
      checkAndHandleScanComplete()
      onConditionSatisfied()

    case CloseRequest if !scanInProgress =>
      sender ! CloseAck(self)

    case CloseRequest if scanInProgress =>
      val actorsToClose = processorActors.values.toSet + persistenceManager
      onCloseRequest(self, actorsToClose, sender(), this, availableMedia.isDefined)

    case CloseComplete =>
      onCloseComplete()
      completeScanOperation()

    case GetMetaDataFileInfo =>
      persistenceManager forward GetMetaDataFileInfo

    case removeMsg: RemovePersistentMetaData =>
      if (scanInProgress) {
        sender ! RemovePersistentMetaDataResult(removeMsg, Set.empty)
      } else {
        persistenceManager forward removeMsg
      }
  }

  /**
    * Creates a meta data processing actor for the specified root path.
    *
    * @param root   the root path
    * @param result the scan result object for this path
    * @return the new processing actor
    */
  private def createProcessorActor(root: Path, result: EnhancedMediaScanResult): ActorRef =
    createChildActor(MetaDataExtractionActor(self, result.fileUriMapping, ExtractorFactory,
      config.rootFor(root).getOrElse(DefaultMediaRoot).processorCount, config.processingTimeout))

  /**
    * Prepares a new scan operation. Initializes some internal state.
    */
  private def initiateNewScan(): Unit = {
    persistenceManager ! ScanForMetaDataFiles
    mediaMap = Map.empty
    completedMedia = Set(MediumID.UndefinedMediumID)
    scanInProgress = true
  }

  /**
    * Prepares the handler object for a medium. If this medium is already known,
    * the existing handler is updated. Otherwise (which should be the default
    * case except for the undefined medium ID), a new handler object is
    * initialized.
    *
    * @param e an entry from the map of media files from a scan result object
    */
  private def prepareHandlerForMedium(e: (MediumID, List[FileData])): Unit = {
    val mediumID = e._1
    val handler = mediaMap.getOrElse(mediumID, new MediumDataHandler(mediumID))
    handler expectMediaFiles e._2
    mediaMap += mediumID -> handler
  }

  /**
    * Handles a meta data processing result.
    *
    * @param mediumID the ID of the affected medium
    * @param result   the result to be handled
    * @return a flag whether this is a valid result
    */
  private def handleProcessingResult(mediumID: MediumID, result: MetaDataProcessingResult):
  Boolean = {
    val optHandler = mediaMap get mediumID
    optHandler.exists(processMetaDataResult(mediumID, result, _))
  }

  /**
    * Processes a meta data result that has been produced by a child actor. The
    * result is added to the responsible handler. If necessary, listeners are
    * notified.
    *
    * @param mediumID the ID of the affected medium
    * @param result  the processing result
    * @param handler the handler for this medium
    * @return a flag whether this is a valid result
    */
  private def processMetaDataResult(mediumID: MediumID, result: MetaDataProcessingResult,
                                    handler: MediumDataHandler): Boolean =
    if (handler.resultReceived(result)) {
      if (handler.isComplete) {
        completedMedia += mediumID
      }
      true
    } else false

  /**
    * Checks whether the scan for meta data is now complete. If this is the
    * case, the corresponding steps are done.
    */
  private def checkAndHandleScanComplete(): Unit = {
    if (allMediaProcessingResultsReceived) {
      persistenceManager ! PersistentMetaDataManagerActor.ScanCompleted
      completeScanOperation()
    }
  }

  /**
    * Performs all steps required to gracefully terminate the current scan
    * operation.
    */
  private def completeScanOperation(): Unit = {
    scanInProgress = false
    availableMedia = None
    completedMedia = Set.empty
    processorActors.values.foreach(context.stop)
    processorActors = Map.empty
  }

  /**
    * Returns a flag whether the processing results for all media have been
    * received. This is used to find out when a scan is complete.
    *
    * @return '''true''' if all results have been arrived; '''false'''
    *         otherwise
    */
  private def allMediaProcessingResultsReceived: Boolean =
  availableMedia exists (m => m.keySet subsetOf completedMedia)
}
