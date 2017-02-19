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
import de.oliver_heger.linedj.archive.media.{EnhancedMediaScanResult, MediaScanStarts}
import de.oliver_heger.linedj.io.CloseHandlerActor.CloseComplete
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest, CloseSupport, FileData}
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.metadata._
import de.oliver_heger.linedj.shared.archive.union.{MediaContribution, MetaDataProcessingResult}
import de.oliver_heger.linedj.utils.ChildActorFactory

object MetaDataManagerActor {

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
 * The central actor class for managing meta data extraction.
 *
 * This actor is notified by the media manager actor when the content of a root
 * directory structure has been scanned; it then receives a
 * [[de.oliver_heger.linedj.archive.media.MediaScanResult]] message. This message is
 * handled by creating a new child actor of type [[MediumProcessorActor]] which
 * is then told to extract all meta data for the files on this medium.
 *
 * The extracting of meta data over complex directory structures can be a time
 * consuming process; it may take a while until complete results are available.
 * Therefore, this manager actor takes a different approach for exposing
 * information to clients than the media manager actor (which may delay
 * responses until all information is there): it returns the information
 * currently available, but allows the caller to register itself as listener.
 * Listeners receive notifications when more meta data is available or when a
 * whole medium has been processed.
 *
 * This actor supports two types of event listeners: Generic meta data state
 * listeners receive notifications about important state changes of this
 * actor, e.g. when a new scan for meta data starts or progress notifications
 * during a scan operation.
 *
 * Meta data medium listeners are only interested in a specific medium. On the
 * first call to this actor it is checked whether the requested medium has
 * already been fully processed. If this is case, the meta data is returned
 * directly, and the caller is not registered as a listener. Otherwise, the
 * caller is sent the meta data currently available and is registered as
 * listener (if requested; this can be controlled by a flag). Listeners are
 * then notified when a configurable chunk of meta data becomes available. When
 * the monitored medium has been fully processed the listener registration is
 * removed automatically.
 *
 * More information about the messages supported by this actor and the overall
 * protocol can be found in the description of the message classes defined in
 * the companion object. In addition to these messages, this actor also deals
 * with some messages that target the persistent meta data manager actor -
 * which is an internal actor and not part of the official interface of the
 * media archive. Therefore, messages served by the persistence manager are
 * passed to this actor and just forwarded. This includes messages related to
 * the current set of persistent meta data files.
 *
 * @param config the central configuration object
 * @param persistenceManager reference to the persistence manager actor
 * @param metaDataUnionActor reference to the meta data union actor
 */
class MetaDataManagerActor(config: MediaArchiveConfig, persistenceManager: ActorRef,
                           metaDataUnionActor: ActorRef) extends Actor with ActorLogging {
  this: ChildActorFactory with CloseSupport =>

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
      else processorActors + (root -> createChildActor(MediumProcessorActor(result, config)))
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
