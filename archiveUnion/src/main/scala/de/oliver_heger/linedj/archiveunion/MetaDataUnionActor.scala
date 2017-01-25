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

package de.oliver_heger.linedj.archiveunion

import akka.actor.{Actor, ActorLogging, ActorRef, Terminated}
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata._

object MetaDataUnionActor {

  /**
    * A message processed by [[MetaDataUnionActor]] telling it that a component
    * of the media archive has been removed. This causes the actor to remove
    * all meta data associated with this archive component.
    *
    * @param archiveCompID the archive component ID
    */
  case class ArchiveComponentRemoved(archiveCompID: String)

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
  * An actor class responsible for managing a union of the meta data for all
  * songs currently available in the union media archive.
  *
  * This actor manages meta data for media files contributed by the single
  * components of the media archive. In order to contribute meta data, an
  * archive component has to do the following interactions with this actor:
  *
  *  - A [[MediaContribution]] message has to be sent listing all media and
  * files a component wants to contribute.
  *  - For each file part of the contribution a [[MetaDataProcessingResult]]
  * message has to be sent.
  *
  * This protocol allows this actor to determine whether all meta data has been
  * received or whether processing results are still pending. This is required
  * to keep track of a life-cycle of scan operations for meta data.
  *
  * This actor combines the processing results received from the archive
  * components and allows querying them. Clients also have the option to
  * register as listeners for a specific medium. Depending on the way the meta
  * data for a medium is obtained, it may take a while until all data is
  * available. Therefore, a client asking for the data of a specific medium
  * is first sent the results already present. If the data is not yet complete
  * and the client passes a specific flag, it is registered as listener for
  * this medium and receives further notification when more data becomes
  * available. When the monitored medium has been fully processed the listener
  * registration is removed automatically.
  *
  * In addition to this listener mechanism for monitoring a specific medium,
  * this actor supports generic meta data state listeners that receive
  * notifications about important state changes of this actor, e.g. when a new
  * scan for meta data starts or progress notifications during a scan
  * operation. (Scan operations are actually executed by archive components;
  * but by tracking the messages received from components, this actor can
  * determine when such an operation is in progress and send corresponding
  * event notifications.)
  */
class MetaDataUnionActor(config: MediaArchiveConfig) extends Actor with ActorLogging {

  import MetaDataUnionActor._

  /** A helper object for generating URIs. */
  private val uriHandler = new MediaFileUriHandler

  /**
    * A map for storing the extracted meta data for all media.
    */
  private val mediaMap = collection.mutable.Map.empty[MediumID, MediumDataHandler]

  /** Stores the listeners registered for specific media. */
  private val mediumListeners =
    collection.mutable.Map.empty[MediumID, List[(ActorRef, Int)]]

  /** A list with the currently registered state listeners. */
  private var stateListeners = Set.empty[ActorRef]

  /** A set with IDs for media which have already been completed. */
  private var completedMedia = Set.empty[MediumID]

  /** A flag whether a scan is currently in progress. */
  private var scanInProgress = false

  /** The number of currently processed songs. */
  private var currentSongCount = 0

  /** The current duration of all processed songs. */
  private var currentDuration = 0L

  /** The current size of all processed songs. */
  private var currentSize = 0L

  override def receive: Receive = {
    case MediaContribution(files) =>
      scanInProgress = true
      fireStateEvent(MetaDataScanStarted)
      files foreach prepareHandlerForMedium

    case result: MetaDataProcessingResult =>
      handleProcessingResult(result.mediumID, result)
      if (isUnassignedMedium(result.mediumID)) {
        // update global unassigned list
        handleProcessingResult(MediumID.UndefinedMediumID, result)
      }
      currentSongCount += 1
      currentDuration += result.metaData.duration getOrElse 0
      currentSize += result.metaData.size

    case GetMetaData(mediumID, registerAsListener, registrationID) =>
      mediaMap get mediumID match {
        case None =>
          sender ! UnknownMedium(mediumID)

        case Some(handler) =>
          handler.metaData foreach (sendMetaDataResponse(sender, _, registrationID))
          if (registerAsListener && !handler.isComplete) {
            val newListeners = (sender(), registrationID) :: mediumListeners.getOrElse(mediumID,
              Nil)
            mediumListeners(mediumID) = newListeners
          }
      }

    case RemoveMediumListener(mediumID, listener) =>
      val listeners = mediumListeners.getOrElse(mediumID, Nil)
      val updatedListeners = listeners filterNot (_._1 == listener)
      if (updatedListeners.nonEmpty) {
        mediumListeners(mediumID) = updatedListeners
      } else {
        mediumListeners remove mediumID
      }

    case AddMetaDataStateListener(listener) =>
      stateListeners = stateListeners + listener
      listener ! createStateUpdatedEvent()
      context watch listener
      log.info("Added state listener.")

    case RemoveMetaDataStateListener(listener) =>
      if (removeStateListenerActor(listener)) {
        context unwatch listener
        log.info("Removed state listener.")
      }

    case Terminated(actor) =>
      // a state listener actor died, so remove it from the set
      removeStateListenerActor(actor)
      log.warning("State listener terminated. Removed from collection.")
  }

  /**
    * Returns a set with the currently registered state listeners. This is
    * mainly for testing purposes.
    *
    * @return a set with the registered state listeners
    */
  def registeredStateListeners: Set[ActorRef] = stateListeners

  /**
    * Prepares the handler object for a medium. If this medium is already known,
    * the existing handler is updated. Otherwise (which should be the default
    * case except for the undefined medium ID), a new handler object is
    * initialized.
    *
    * @param e an entry from the map of media files from a scan result object
    */
  private def prepareHandlerForMedium(e: (MediumID, Iterable[FileData])): Unit = {
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
        fireStateEvent(createStateUpdatedEvent())
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
    lazy val chunkMsg = chunk
    mediumListeners get mediumID foreach { l =>
      l foreach (t => sendMetaDataResponse(t._1, chunkMsg, t._2))
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
    if (hasUndefinedMedium) {
      fireStateEvent(MediumMetaDataCompleted(MediumID.UndefinedMediumID))
    }
    fireStateEvent(createStateUpdatedEvent()) // a final update event
    fireStateEvent(MetaDataScanCompleted)
    completedMedia = Set.empty
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
    (mediaMap.keySet - MediumID.UndefinedMediumID) subsetOf completedMedia

  /**
    * Sends a meta data response message to the specified actor.
    *
    * @param actor the receiving actor
    * @param chunk the chunk of data to be sent
    * @param regID the actor's registration ID
    */
  private def sendMetaDataResponse(actor: ActorRef, chunk: MetaDataChunk, regID: Int): Unit = {
    actor ! MetaDataResponse(chunk, regID)
  }

  /**
    * Creates a ''MetaDataStateUpdated'' event with the current statistics
    * information.
    *
    * @return the state object
    */
  private def createStateUpdatedEvent(): MetaDataStateUpdated =
    MetaDataStateUpdated(MetaDataState(mediaCount = completedMedia.size, songCount =
      currentSongCount,
      duration = currentDuration, size = currentSize, scanInProgress = scanInProgress))

  /**
    * Sends the specified event to all registered state listeners.
    *
    * @param event the event to be sent
    */
  private def fireStateEvent(event: => MetaDataStateEvent): Unit = {
    lazy val msg = event
    registeredStateListeners foreach (_ ! msg)
  }

  /**
    * Removes a state listener actor from the internal collection. Result is
    * '''true''' if the listener could actually be removed.
    *
    * @param actor the listener actor
    * @return a flag whether this listener actor was removed
    */
  private def removeStateListenerActor(actor: ActorRef): Boolean = {
    val oldListeners = stateListeners
    stateListeners = stateListeners filterNot (_ == actor)
    oldListeners != stateListeners
  }
}
