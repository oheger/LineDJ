/*
 * Copyright 2015-2016 The Developers Team.
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
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest, FileData}
import de.oliver_heger.linedj.archive.media.{EnhancedMediaScanResult, MediaFileUriHandler, MediaScanStarts}
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.metadata._
import de.oliver_heger.linedj.utils.ChildActorFactory

object MetaDataManagerActor {

  private class MetaDataManagerActorImpl(config: MediaArchiveConfig, persistenceManager: ActorRef)
    extends MetaDataManagerActor(config, persistenceManager) with ChildActorFactory

  /**
    * Returns creation properties for an actor instance of this type.
    *
    * @param config             the server configuration object
    * @param persistenceManager reference to the persistence manager actor
    * @return creation properties for a new actor instance
    */
  def apply(config: MediaArchiveConfig, persistenceManager: ActorRef): Props =
    Props(classOf[MetaDataManagerActorImpl], config, persistenceManager)

  /**
   * Returns a flag whether the specified medium ID refers to files not
   * assigned to a medium, but is not the global undefined medium. Such IDs
   * have to be treated in a special way because the global undefined medium
   * has to be updated.
    *
    * @param mediumID the medium ID to check
   * @return a flag whether this is an unassigned medium
   */
  private def isUnassignedMedium(mediumID: MediumID): Boolean =
    mediumID.mediumDescriptionPath.isEmpty && mediumID != MediumID.UndefinedMediumID

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
 * There are two types of listeners. A meta data completion listener receives
 * notifications whenever a medium has been fully processed. At this time
 * the meta data for this medium can be queried, and complete data is
 * returned.
 *
 * A meta data medium listener is only interested for a specific medium. On the
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
 * the companion object.
 *
 * @param config the central configuration object
 * @param persistenceManager reference to the persistence manager actor
 */
class MetaDataManagerActor(config: MediaArchiveConfig, persistenceManager: ActorRef) extends Actor
  with ActorLogging {
  this: ChildActorFactory =>

  import MetaDataManagerActor._

  /** A helper object for generating URIs. */
  private val uriHandler = new MediaFileUriHandler

  /**
    * A map for storing the extracted meta data for all media.
    */
  private val mediaMap = collection.mutable.Map.empty[MediumID, MediumDataHandler]

  /** Stores the listeners registered for specific media. */
  private val mediumListeners = collection.mutable.Map.empty[MediumID, List[ActorRef]]

  /** A list with the currently registered state listeners. */
  private var stateListeners = List.empty[ActorRef]

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

  /**
    * A set that stores the actors from which a Close Ack is pending. This is
    * used to determine when a scan can be canceled.
    */
  private var pendingCloseAck = Set.empty[ActorRef]

  /** A flag whether a scan is currently in progress. */
  private var scanInProgress = false

  /** Stores information about a pending cancel request. */
  private var cancelRequest: Option[ActorRef] = None

  /** The number of currently processed songs. */
  private var currentSongCount = 0

  /** The current duration of all processed songs. */
  private var currentDuration = 0L

  /** The current size of all processed songs. */
  private var currentSize = 0L

  override def receive: Receive = {
    case MediaScanStarts if !scanInProgress =>
      initiateNewScan()

    case esr: EnhancedMediaScanResult if scanInProgress && cancelRequest.isEmpty =>
      persistenceManager ! esr
      esr.scanResult.mediaFiles foreach prepareHandlerForMedium

    case result: MetaDataProcessingResult if cancelRequest.isEmpty =>
      handleProcessingResult(result.mediumID, result)
      if (isUnassignedMedium(result.mediumID)) {
        // update global unassigned list
        handleProcessingResult(MediumID.UndefinedMediumID, result)
      }
      currentSongCount += 1
      currentDuration += result.metaData.duration getOrElse 0
      currentSize += result.metaData.size

    case UnresolvedMetaDataFiles(mid, files, result) =>
      val root = result.scanResult.root
      val actorMap = if (processorActors contains root) processorActors
      else processorActors + (root -> createChildActor(MediumProcessorActor(result, config)))
      actorMap(root) ! ProcessMediaFiles(mid, files)
      processorActors = actorMap

    case AvailableMedia(media) =>
      availableMedia = Some(media)
      checkAndHandleScanComplete()
      checkAndHandleCancelRequest()

    case CloseRequest if !scanInProgress =>
      sender ! CloseAck(self)

    case CloseRequest if cancelRequest.isEmpty =>
      cancelRequest = Some(sender())
      pendingCloseAck = processorActors.values.toSet + persistenceManager
      pendingCloseAck foreach (_ ! CloseRequest)
      fireStateEvent(MetaDataScanCanceled)

    case CloseAck(actor) =>
      pendingCloseAck -= actor
      checkAndHandleCancelRequest()

    case GetMetaData(mediumID, registerAsListener) =>
      mediaMap get mediumID match {
        case None =>
          sender ! UnknownMedium(mediumID)

        case Some(handler) =>
          handler.metaData foreach (sender ! _)
          if (registerAsListener && !handler.isComplete) {
            val newListeners = sender() :: mediumListeners.getOrElse(mediumID, Nil)
            mediumListeners(mediumID) = newListeners
          }
      }

    case RemoveMediumListener(mediumID, listener) =>
      val listeners = mediumListeners.getOrElse(mediumID, Nil)
      val updatedListeners = listeners filterNot (_ == listener)
      if (updatedListeners.nonEmpty) {
        mediumListeners(mediumID) = updatedListeners
      } else {
        mediumListeners remove mediumID
      }

    case AddMetaDataStateListener(listener) =>
      stateListeners = listener :: stateListeners
      listener ! ccreateStateUpdatedEvent

    case RemoveMetaDataStateListener(listener) =>
      stateListeners = stateListeners filterNot (_ == listener)
  }

  /**
    * Prepares a new scan operation. Initializes some internal state.
    */
  private def initiateNewScan(): Unit = {
    mediaMap.clear()
    scanInProgress = true
    currentSize = 0
    currentDuration = 0
    currentSongCount = 0
    fireStateEvent(MetaDataScanStarted)
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
    val handler = mediaMap.getOrElseUpdate(mediumID, createHandlerForMedium(mediumID))
    handler expectMediaFiles e._2

    if (isUnassignedMedium(mediumID)) {
      prepareHandlerForMedium((MediumID.UndefinedMediumID, e._2))
    }
  }

  /**
    * Creates a handler object for the specified medium.
    *
    * @param mediumID the medium ID
    * @return the handler for this medium
    */
  private def createHandlerForMedium(mediumID: MediumID): MediumDataHandler =
  if (MediumID.UndefinedMediumID == mediumID) new MediumDataHandler(mediumID) {
    override protected def extractUri(result: MetaDataProcessingResult): String =
      uriHandler.generateUndefinedMediumUri(result.mediumID, result.uri)
  } else new MediumDataHandler(mediumID) {
    override protected def extractUri(result: MetaDataProcessingResult): String =
      result.uri
  }

  /**
    * Handles a meta data processing result.
    *
    * @param mediumID the ID of the affected medium
    * @param result   the result to be handled
    */
  private def handleProcessingResult(mediumID: MediumID, result: MetaDataProcessingResult): Unit = {
    mediaMap get mediumID foreach processMetaDataResult(mediumID, result)
  }

  /**
    * Processes a meta data result that has been produced by a child actor. The
    * result is added to the responsible handler. If necessary, listeners are
    * notified.
    *
    * @param mediumID the ID of the affected medium
    * @param result   the processing result
    * @param handler  the handler for this medium
    */
  private def processMetaDataResult(mediumID: MediumID,
                                    result: MetaDataProcessingResult)(handler: MediumDataHandler)
  : Unit = {
    if (handler.storeResult(result, config.metaDataUpdateChunkSize, config.metaDataMaxMessageSize)
    (handleCompleteChunk(mediumID))) {
      mediumListeners remove mediumID
      completedMedia += mediumID
      if (mediumID != MediumID.UndefinedMediumID) {
        // the undefined medium is handled at the very end of the scan
        fireStateEvent(MediumMetaDataCompleted(mediumID))
        fireStateEvent(ccreateStateUpdatedEvent())
      }
      checkAndHandleScanComplete()
    }
  }

  /**
    * Handles a new chunk of mata data that became available. This method
    * notifies the listeners registered for this medium.
    *
    * @param mediumID the ID of the affected medium
    * @param chunk    the chunk
    */
  private def handleCompleteChunk(mediumID: MediumID)(chunk: => MetaDataChunk): Unit = {
    mediumListeners get mediumID foreach { l =>
      val chunkMsg = chunk
      l foreach (_ ! chunkMsg)
    }
  }

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
    if (hasUndefinedMedium) {
      fireStateEvent(MediumMetaDataCompleted(MediumID.UndefinedMediumID))
    }
    completedMedia = Set.empty
    fireStateEvent(MetaDataScanCompleted)
  }

  /**
    * Checks whether the current list of completed media contains at least one
    * instance without a settings file (an undefined medium).
    *
    * @return a flag whether an undefined medium has been encountered
    */
  private def hasUndefinedMedium: Boolean =
  completedMedia.exists(_.mediumDescriptionPath.isEmpty)

  /**
    * Returns a flag whether the processing results for all media have been
    * received. This is used to find out when a scan is complete.
    *
    * @return '''true''' if all results have been arrived; '''false'''
    *         otherwise
    */
  private def allMediaProcessingResultsReceived: Boolean =
  availableMedia exists (m => m.keySet subsetOf completedMedia)

  /**
    * Checks whether a cancel request is pending and whether it can be served
    * now. If so, the current scan operation is canceled.
    */
  private def checkAndHandleCancelRequest(): Unit = {
    cancelRequest foreach { rec =>
      if (pendingCloseAck.isEmpty && availableMedia.isDefined) {
        rec ! CloseAck(self)
        cancelRequest = None
        mediumListeners.clear()
        completeScanOperation()
      }
    }
  }

  /**
    * Creates a ''MetaDataStateUpdated'' event with the current statistics
    * information.
    *
    * @return the state object
    */
  private def ccreateStateUpdatedEvent(): MetaDataStateUpdated =
  MetaDataStateUpdated(MetaDataState(mediaCount = completedMedia.size, songCount = currentSongCount,
    duration = currentDuration, size = currentSize, scanInProgress = scanInProgress))

  /**
    * Sends the specified event to all registered state listeners.
    *
    * @param event the event to be sent
    */
  private def fireStateEvent(event: => MetaDataStateEvent): Unit = {
    lazy val msg = event
    stateListeners foreach (_ ! msg)
  }
}