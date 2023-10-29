/*
 * Copyright 2015-2023 The Developers Team.
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

import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediaFileUri, MediumID, ScanAllMedia}
import de.oliver_heger.linedj.shared.archive.metadata._
import de.oliver_heger.linedj.shared.archive.union._
import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Terminated}

object MetaDataUnionActor {

  /**
    * A message processed by ''MetaDataUnionActor'' that allows an enhanced
    * request for meta data. This message is handled like a normal
    * [[GetFilesMetaData]] message, but in addition a mapping of medium IDs is
    * contained in the message. This mapping is applied to the requested media.
    * Background is that meta data requests can contain a medium checksum;
    * this may cause some medium IDs to be changed.
    *
    * @param request   the actual request for meta data
    * @param idMapping the mapping to be applied to ''MediaFileID'' objects
    */
  case class GetFilesMetaDataWithMapping(request: GetFilesMetaData,
                                         idMapping: Map[MediaFileID, MediumID])

  /**
    * An internally used data class to handle removed archive components. Such
    * an event may be processed at a later point in time; therefore, the
    * relevant information has to be stored.
    *
    * @param componentID the archive component ID
    * @param sender      the sending actor (for sending a confirmation)
    * @param counters    the stats of the component affected
    */
  private case class RemovedComponentData(componentID: String, sender: ActorRef,
                                          counters: ComponentCounters)

  /**
    * An internally used data class for calculating statistics for single
    * archive components.
    *
    * During processing of results, for each archive component an instance of
    * this class is managed; the counters are increased accordingly.
    *
    * @param mediumCount the number of media in this component
    * @param songCount   the number of songs
    * @param size        the size of the media files in bytes
    * @param duration    the accumulated playback duration in milliseconds
    */
  private case class ComponentCounters(mediumCount: Int,
                                       songCount: Int,
                                       size: Long,
                                       duration: Long) {
    /**
      * Updates the counters in this instance based on the passed in result
      * object. For a success result, the counters are incremented accordingly.
      *
      * @param result the result object
      * @return the updated ''ComponentCounters'' instance
      */
    def update(result: MetaDataProcessingResult): ComponentCounters =
      result match {
        case success: MetaDataProcessingSuccess =>
          copy(songCount = songCount + 1, size = size + success.metaData.size,
            duration = duration + success.metaData.duration.getOrElse(0))
        case _ => this
      }

    /**
      * Returns an updated instance with an incremented counter for the media.
      *
      * @return the updated ''ComponentCounters'' instance
      */
    def mediumCompleted(): ComponentCounters = copy(mediumCount = mediumCount + 1)
  }

  /** Constant for an initial counters object. */
  private val InitialComponentCounters = ComponentCounters(0, 0, 0, 0)

  /**
    * Updates the complete state of the given ''MetaDataChunk''. Only if
    * necessary, a modified copy of the given chunk is created.
    *
    * @param chunk    the chunk in question
    * @param complete the desired complete state
    * @return the chunk with this complete state
    */
  private def chunkWithCompletionState(chunk: MetaDataChunk, complete: Boolean): MetaDataChunk =
    if (chunk.complete == complete) chunk
    else chunk.copy(complete = complete)
}

/**
  * An actor class responsible for managing a union of the meta data for all
  * songs currently available in the union media archive.
  *
  * This actor manages meta data for media files contributed by the single
  * components of the media archive. In order to contribute meta data, an
  * archive component has to do the following interactions with this actor:
  *
  *  - At the beginning of the interaction an [[UpdateOperationStarts]] message
  *    has to be sent to announce that now meta data will be added.
  *  - A [[MediaContribution]] message has to be sent listing all media and
  *    files a component wants to contribute.
  *  - For each file part of the contribution a [[MetaDataProcessingSuccess]]
  *    message has to be sent.
  *  - These two steps can be repeated, e.g. for multiple media managed by the
  *    archive component.
  *  - When the component will send no more contributions, it should announce
  *    this by sending a [[UpdateOperationCompleted]] message.
  *  - When data owned by a component becomes invalid and should be replaced
  *    with newer information the same steps have to be followed, but an
  *    [[ArchiveComponentRemoved]] message should be sent first; this removes
  *    all data related to this component. To be sure that the message has been
  *    processed, the confirmation should be waited for before actually sending
  *    data.
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

  /**
    * A map for storing the extracted meta data for all media.
    */
  private val mediaMap = collection.mutable.Map.empty[MediumID, MediumDataHandler]

  /** Stores the listeners registered for specific media. */
  private val mediumListeners =
    collection.mutable.Map.empty[MediumID, List[(ActorRef, Int)]]

  /** Stores the listeners for the global undefined medium. */
  private var undefinedMediumListeners = List.empty[(ActorRef, Int)]

  /** A list with the currently registered state listeners. */
  private var stateListeners = Set.empty[ActorRef]

  /** A set with IDs for media which have already been completed. */
  private var completedMedia = Set.empty[MediumID]

  /** A set with the actors that have an operation in progress. */
  private var processorActors = Set.empty[ActorRef]

  /**
    * Stores information about archive components that have been removed. Such
    * remove operations can only be handled at the end of a scan; therefore,
    * this data has to be stored temporarily.
    */
  private var removedComponentData = List.empty[RemovedComponentData]

  /** A map with statistics about the currently known archive component IDs. */
  private var archiveComponentStats = Map.empty[String, ComponentCounters]

  /** A flag whether a scan is currently in progress. */
  private var scanInProgress = false

  /** A counters object for the whole union archive. */
  private var totalCounters = InitialComponentCounters

  override def receive: Receive = {
    case UpdateOperationStarts(processor) =>
      val procRef = obtainProcessorActor(processor)
      context watch procRef
      processorActors += procRef
      if (processorActors.size == 1) {
        fireStateEvent(MetaDataUpdateInProgress)
      }

    case UpdateOperationCompleted(processor) =>
      val procRef = obtainProcessorActor(processor)
      context unwatch procRef
      removeProcessorActor(procRef)

    case MediaContribution(files) =>
      log.info("Received MediaContribution.")
      if (!scanInProgress) {
        scanInProgress = true
        fireStateEvent(MetaDataScanStarted)
        log.info("Scan starts.")
      }
      files foreach prepareHandlerForMedium

    case result: MetaDataProcessingResult if scanInProgress =>
      val completedMediaSize = completedMedia.size
      if (handleProcessingResult(result.mediumID, result)) {
        updateStatistics(result)
      }
      if (completedMedia.size != completedMediaSize) {
        checkAndHandleScanComplete()
      }

    case ScanAllMedia if !scanInProgress =>
      initiateNewScan()

    case GetMetaData(mediumID, registerAsListener, registrationID) if mediumID == MediumID.UndefinedMediumID =>
      handleGetMetaDataForUndefinedMedium(registerAsListener, registrationID)

    case GetMetaData(mediumID, registerAsListener, registrationID) =>
      handleGetMetaData(mediumID, registerAsListener, registrationID)

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

    case req: GetFilesMetaData =>
      handleFilesMetaDataRequest(req, Map.empty)

    case GetFilesMetaDataWithMapping(request, idMapping) =>
      handleFilesMetaDataRequest(request, idMapping)

    case ArchiveComponentRemoved(archiveCompID) =>
      handleRemovedArchiveComponent(archiveCompID)
      log.info(s"Archive component removed: $archiveCompID.")

    case GetArchiveComponentStatistics(archiveComponentID) =>
      handleStatsRequest(archiveComponentID)

    case CloseRequest =>
      if (scanInProgress) {
        fireStateEvent(MetaDataScanCanceled)
        mediumListeners.clear()
        completeScanOperation()
      }
      sender() ! CloseAck(self)

    case Terminated(actor) =>
      // a state listener or processor actor died, so remove it from the set(s)
      if (removeStateListenerActor(actor)) {
        log.warning("State listener terminated. Removed from collection.")
      }
      if (removeProcessorActor(actor)) {
        log.warning("A processor actor terminated. Removed from collection.")
      }
  }

  /**
    * Handles a request for meta data for the undefined medium. For this
    * synthetic medium no dedicated handler exists; therefore, such requests
    * need to be treated in a special way.
    *
    * @param registerAsListener flag whether a listener is to be registered
    * @param registrationID     the registration ID
    */
  private def handleGetMetaDataForUndefinedMedium(registerAsListener: Boolean, registrationID: Int): Unit = {
    val chunks = mediaMap.filter(_._1.isArchiveUndefinedMedium)
      .flatMap(_._2.metaData)
    if (chunks.isEmpty) {
      if (scanInProgress && registerAsListener) {
        registerUndefinedMediumListener(registrationID)
      } else {
        sender() ! UnknownMedium(MediumID.UndefinedMediumID)
      }

    } else {
      val (first, last) = if (scanInProgress) (chunks, None)
      else (chunks.init, chunks.lastOption)
      first foreach { c =>
        sendMetaDataResponse(sender(), chunkWithCompletionState(c, complete = false), registrationID)
      }
      last foreach { c =>
        sendMetaDataResponse(sender(), chunkWithCompletionState(c, complete = true), registrationID)
      }

      if (registerAsListener && scanInProgress) {
        registerUndefinedMediumListener(registrationID)
      }
    }
  }

  /**
    * Handles a request for meta data for a specific medium.
    *
    * @param mediumID           the medium ID
    * @param registerAsListener flag whether a listener is to be registered
    * @param registrationID     the registration ID
    */
  private def handleGetMetaData(mediumID: MediumID, registerAsListener: Boolean, registrationID: Int): Unit = {
    mediaMap get mediumID match {
      case None =>
        if (scanInProgress && registerAsListener) {
          registerMediumListener(mediumID, registrationID)
        } else {
          sender() ! UnknownMedium(mediumID)
        }

      case Some(handler) =>
        handler.metaData foreach (sendMetaDataResponse(sender(), _, registrationID))
        if (registerAsListener && !handler.isComplete) {
          registerMediumListener(mediumID, registrationID)
        }
    }
  }

  /**
    * Adds a registration for a medium listener.
    *
    * @param mediumID       the ID of the medium in question
    * @param registrationID the registration ID
    */
  private def registerMediumListener(mediumID: MediumID, registrationID: Int): Unit = {
    val newListeners = (sender(), registrationID) :: mediumListeners.getOrElse(mediumID, Nil)
    mediumListeners(mediumID) = newListeners
  }

  /**
    * Adds a listener registration for the global undefined medium.
    *
    * @param registrationID the registration ID
    */
  private def registerUndefinedMediumListener(registrationID: Int): Unit = {
    undefinedMediumListeners = (sender(), registrationID) :: undefinedMediumListeners
  }

  /**
    * Returns a set with the currently registered state listeners. This is
    * mainly for testing purposes.
    *
    * @return a set with the registered state listeners
    */
  def registeredStateListeners: Set[ActorRef] = stateListeners

  /**
    * Obtains the processor actor from the given optional reference. If a
    * processor actor has been provided explicitly, it is used. Otherwise, it
    * is assumed that the sender is the processor actor.
    *
    * @param optProcessor the optional reference to the processor actor
    * @return the processor actor to be used
    */
  private def obtainProcessorActor(optProcessor: Option[ActorRef]): ActorRef =
    optProcessor getOrElse sender()

  /**
    * Removes an actor from the set of active processors. This method is called
    * when the update operation of this processor is completed normally or when
    * the processor actor dies. In both cases, the set of active processors has
    * to be updated, and events have to be sent if necessary.
    *
    * @param processor the processor actor to be removed
    * @return a flag whether the actor could be removed
    */
  private def removeProcessorActor(processor: ActorRef): Boolean = {
    val nextProcessors = processorActors - processor
    if (nextProcessors != processorActors) {
      processorActors = nextProcessors
      if (nextProcessors.isEmpty) {
        fireStateEvent(MetaDataUpdateCompleted)
      }
      true
    } else false
  }

  /**
    * Prepares a new scan operation. Initializes some internal state.
    */
  private def initiateNewScan(): Unit = {
    fireStateEvent(MetaDataScanStarted)
    mediaMap.clear()
    scanInProgress = true
    totalCounters = InitialComponentCounters
    completedMedia = Set.empty
    archiveComponentStats = Map.empty
  }

  /**
    * Prepares the handler object for a medium. If this medium is already known,
    * the existing handler is updated. Otherwise (which should be the default
    * case except for the undefined medium ID), a new handler object is
    * initialized.
    *
    * @param e an entry from the map of media files from a scan result object
    */
  private def prepareHandlerForMedium(e: (MediumID, Iterable[MediaFileUri])): Unit = {
    val mediumID = e._1
    val handler = mediaMap.getOrElseUpdate(mediumID, new MediumDataHandler(mediumID))
    handler expectMediaFiles e._2
  }

  /**
    * Handles a meta data processing result.
    *
    * @param mediumID the ID of the affected medium
    * @param result   the result to be handled
    * @return a flag whether the medium ID could be resolved
    */
  private def handleProcessingResult(mediumID: MediumID, result: MetaDataProcessingResult):
  Boolean =
    mediaMap.get(mediumID) match {
      case Some(handler) =>
        processMetaDataResult(mediumID, result, handler)
        true
      case None => false
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
  private def processMetaDataResult(mediumID: MediumID, result: MetaDataProcessingResult,
                                    handler: MediumDataHandler): Unit = {
    if (handler.storeResult(result, config.metaDataUpdateChunkSize, config.metaDataMaxMessageSize)
    (handleCompleteChunk(mediumID))) {
      mediumListeners remove mediumID
      completedMedia += mediumID
      totalCounters = totalCounters.mediumCompleted()
      updateComponentStats(mediumID.archiveComponentID)(_.mediumCompleted())
      fireStateEvent(MediumMetaDataCompleted(mediumID))
      fireStateEvent(createStateUpdatedEvent())
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

    if (mediumID.isArchiveUndefinedMedium) {
      lazy val incompleteChunkMsg = chunkWithCompletionState(chunkMsg, complete = false)
      undefinedMediumListeners foreach { t =>
        sendMetaDataResponse(t._1, incompleteChunkMsg, t._2)
      }
    }
  }

  /**
    * Updates statistics for a newly received processing result.
    *
    * @param result the processing result
    */
  private def updateStatistics(result: MetaDataProcessingResult): Unit = {
    totalCounters = totalCounters.update(result)
    updateComponentStats(result.mediumID.archiveComponentID)(_.update(result))
  }

  /**
    * Obtains the ''ComponentCounters'' object for the given component ID. On
    * first access, a new instance is created.
    *
    * @param componentID the component ID
    * @return the ''ComponentCounters'' for this component
    */
  private def fetchComponentStats(componentID: String): ComponentCounters =
    archiveComponentStats.getOrElse(componentID, InitialComponentCounters)

  /**
    * Updates a ''ComponentCounters'' object for a specific component. This
    * function retrieves the current counters (creating a new instance if
    * necessary), applies the given update function, and stores the resulting
    * instance again in the data structure for counters.
    *
    * @param componentID the component ID
    * @param f           the function to update the counters
    */
  private def updateComponentStats(componentID: String)(f: ComponentCounters => ComponentCounters): Unit = {
    val counters = fetchComponentStats(componentID)
    archiveComponentStats += componentID -> f(counters)
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
    log.info("Scan stopped.")
    mediumListeners.remove(MediumID.UndefinedMediumID)
    if (hasUndefinedMedium) {
      fireStateEvent(MediumMetaDataCompleted(MediumID.UndefinedMediumID))
    }
    handlePendingMediumListeners()
    fireStateEvent(createStateUpdatedEvent()) // a final update event
    fireStateEvent(MetaDataScanCompleted)
    updateForRemovedArchiveComponent()
  }

  /**
    * Takes care that pending medium listeners receive a final response
    * message. Such listeners are caused by removed archive components whose
    * data is incomplete. Listeners for the undefined medium always receive
    * such a final message, because as long as the scan is in progress, there
    * can always be a new archive-specific undefined medium.
    */
  private def handlePendingMediumListeners(): Unit = {
    mediumListeners.foreach { e =>
      lazy val chunk = MetaDataChunk(e._1, Map.empty, complete = true)
      e._2 foreach (l => sendMetaDataResponse(l._1, chunk, l._2))
    }
    mediumListeners.clear()

    lazy val lastUndefinedMediumChunk = MetaDataChunk(MediumID.UndefinedMediumID, Map.empty, complete = true)
    undefinedMediumListeners foreach { t =>
      sendMetaDataResponse(t._1, lastUndefinedMediumChunk, t._2)
    }
    undefinedMediumListeners = Nil
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
    * Handles the removal of an archive component. This requires updates on
    * the managed meta data.
    *
    * @param archiveCompID the ID of the removed component
    */
  private def handleRemovedArchiveComponent(archiveCompID: String): Unit = {
    val media = mediaMap.keys.filter(_.archiveComponentID == archiveCompID)
    media foreach mediaMap.remove
    completedMedia --= media

    val counter = fetchComponentStats(archiveCompID)
    archiveComponentStats -= archiveCompID

    removedComponentData = RemovedComponentData(archiveCompID, sender(), counter) :: removedComponentData
    if (!scanInProgress) {
      updateForRemovedArchiveComponent()
    } else {
      checkAndHandleScanComplete()
    }
  }

  /**
    * Actually handles removed archive components. Here the required operations
    * take place. This method cannot be called during a scan operation.
    * Therefore, data about removed components is stored and processed at the
    * end of a scan.
    */
  private def updateForRemovedArchiveComponent(): Unit = {
    removedComponentData foreach { c =>
      totalCounters = totalCounters.copy(mediumCount = totalCounters.mediumCount - c.counters.mediumCount,
        songCount = totalCounters.songCount - c.counters.songCount,
        size = totalCounters.size - c.counters.size,
        duration = totalCounters.duration - c.counters.duration)
      c.sender ! RemovedArchiveComponentProcessed(c.componentID)
    }
    fireStateEvent(MetaDataScanStarted)
    fireStateEvent(createStateUpdatedEvent())
    fireStateEvent(MetaDataScanCompleted)
    removedComponentData = List.empty
  }

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
    MetaDataStateUpdated(MetaDataState(mediaCount = completedMedia.size, songCount = totalCounters.songCount,
      duration = totalCounters.duration, size = totalCounters.size,
      scanInProgress = scanInProgress, updateInProgress = processorActors.nonEmpty,
      archiveCompIDs = archiveComponentStats.keySet))

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

  /**
    * Handles a request for meta data for files. Sends a response with the
    * resolved meta data to the caller.
    *
    * @param req     the request
    * @param mapping a mapping for medium IDs
    */
  private def handleFilesMetaDataRequest(req: GetFilesMetaData,
                                         mapping: Map[MediaFileID, MediumID]): Unit = {
    sender() ! FilesMetaDataResponse(req, resolveFilesMetaData(req, mapping))
  }

  /**
    * Resolves meta data for requested files by querying the data structures
    * managed by this actor. The mapping for medium IDs is taken into account.
    *
    * @param req     the request
    * @param mapping a mapping for medium IDs
    * @return a map with all meta data that could be resolved
    */
  private def resolveFilesMetaData(req: GetFilesMetaData, mapping: Map[MediaFileID, MediumID]):
  List[(MediaFileID, MediaMetaData)] =
    req.files.flatMap { f =>
      val mid = mapping.getOrElse(f, f.mediumID)
      mediaMap.get(mid).flatMap(_.metaDataFor(f.uri)).map((f, _))
    }.toList

  /**
    * Handles a request for statistics of an archive component. The statistics
    * can be extracted from the archive components data structure. If the
    * requested component cannot be resolved, an invalid statistics object is
    * returned.
    *
    * @param archiveComponentID the archive component ID
    */
  private def handleStatsRequest(archiveComponentID: String): Unit = {
    val stats = if (archiveComponentStats contains archiveComponentID) {
      val counters = archiveComponentStats(archiveComponentID)
      ArchiveComponentStatistics(archiveComponentID = archiveComponentID,
        mediaCount = counters.mediumCount, songCount = counters.songCount,
        size = counters.size, duration = counters.duration)
    } else
      ArchiveComponentStatistics(archiveComponentID, -1, -1, -1, -1)
    sender() ! stats
  }
}
