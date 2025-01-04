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

package de.oliver_heger.linedj.archive.metadata

import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.media.{EnhancedMediaScanResult, MediaScanStarts, PathUriConverter}
import de.oliver_heger.linedj.archive.metadata.MetadataManagerActor.ScanResultProcessed
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetadataManagerActor
import de.oliver_heger.linedj.extract.metadata.{MetadataExtractionActor, ProcessMediaFiles}
import de.oliver_heger.linedj.io.CloseHandlerActor.CloseComplete
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest, CloseSupport, FileData}
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediaScanCompleted, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.metadata._
import de.oliver_heger.linedj.shared.archive.union.{MediaContribution, MetadataProcessingResult, UpdateOperationCompleted, UpdateOperationStarts}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props}

import java.nio.file.Path
import scala.collection.immutable.Queue

object MetadataManagerActor:

  /**
    * A message sent by [[MetadataManagerActor]] in response to scan results as
    * an ACK signal.
    *
    * This message is used to implement back-pressure for metadata extraction.
    * It is sent to clients when there is capacity to process further media.
    * The number of media waiting to be processed is defined in the archive
    * configuration.
    */
  case object ScanResultProcessed

  private class MetadataManagerActorImpl(config: MediaArchiveConfig, persistenceManager: ActorRef,
                                         metaDataUnionActor: ActorRef, converter: PathUriConverter)
    extends MetadataManagerActor(config, persistenceManager, metaDataUnionActor, converter)
      with ChildActorFactory with CloseSupport

  /**
    * Returns creation properties for an actor instance of this type.
    *
    * @param config             the server configuration object
    * @param persistenceManager reference to the persistence manager actor
    * @param metadataUnionActor reference to the metadata union actor
    * @param converter          the ''PathUriConverter''
    * @return creation properties for a new actor instance
    */
  def apply(config: MediaArchiveConfig, persistenceManager: ActorRef,
            metadataUnionActor: ActorRef, converter: PathUriConverter): Props =
    Props(classOf[MetadataManagerActorImpl], config, persistenceManager, metadataUnionActor, converter)


/**
  * The central actor class for managing metadata extraction for a local
  * archive.
  *
  * This actor class coordinates the work of the media manager actor (which
  * scans a directory structure with media files) with the persistence
  * manager actor (which manages already extracted metadata for media files).
  * It receives messages about the media files detected in a folder
  * structure that has been scanned. It matches this data against the metadata
  * read from persistent metadata files. For files for which no metadata
  * is available an extraction process is triggered, so that the
  * persistent metadata can be updated.
  *
  * All metadata - either read from persistent files or extracted manually -
  * is sent to a union metadata manager actor. It is then stored in the union
  * archive and can be queried by clients.
  *
  * To avoid an unlimited growth of the queue of media waiting to be processed
  * for metadata extraction, the actor sends ACK messages when there is
  * capacity for more media to be processed. When a medium has been completed
  * it is checked whether there is another client waiting for an ACK.
  *
  * @param config             the central configuration object
  * @param persistenceManager reference to the persistence manager actor
  * @param metadataUnionActor reference to the metadata union actor
  * @param converter          the ''PathUriConverter''
  */
class MetadataManagerActor(config: MediaArchiveConfig, persistenceManager: ActorRef,
                           metadataUnionActor: ActorRef, converter: PathUriConverter) extends Actor with ActorLogging:
  this: ChildActorFactory with CloseSupport =>

  /** The factory for extractor actors. */
  private val ExtractorFactory = new ExtractorActorFactoryImpl(config)

  /**
    * A map for storing the extracted metadata for all media.
    */
  private var mediaMap = Map.empty[MediumID, MediumDataHandler]

  /** A map with the processor actors for the different media roots. */
  private var processorActors = Map.empty[Path, ActorRef]

  /** A set with IDs for media which have already been completed. */
  private var completedMedia = Set.empty[MediumID]

  /** A set with media that are currently processed. */
  private var mediaInProgress = Set.empty[MediumID]

  /** Stores client references waiting for an ACK message. */
  private var pendingAck = Queue.empty[ActorRef]

  /**
    * Stores information about all available media. This is provided by the
    * media manager when the file scan is complete. It is used to determine
    * when the processing of media files is done.
    */
  private var availableMedia: Option[Map[MediumID, MediumInfo]] = None

  /** Stores the client of the current ongoing scan operation. */
  private var scanClient: Option[ActorRef] = None

  override def receive: Receive =
    case MediaScanStarts(client) =>
      if !scanInProgress then
        initiateNewScan(client)
      sender() ! ScanResultProcessed

    case result: MetadataProcessingResult if !isCloseRequestInProgress =>
      if handleProcessingResult(result.mediumID, result) then
        metadataUnionActor ! result
        checkAndHandleScanComplete()

    case UnresolvedMetadataFiles(mid, files, result) =>
      val root = result.scanResult.root
      val actorMap = if processorActors contains root then processorActors
      else processorActors + (root -> createProcessorActor(root))
      actorMap(root) ! ProcessMediaFiles(mid, files, converter.pathToUri)
      processorActors = actorMap

    case esr: EnhancedMediaScanResult if scanInProgress && !isCloseRequestInProgress =>
      val mediaFiles = esr.scanResult.mediaFiles map { e =>
        e._1 -> e._2.map(f => converter.pathToUri(f.path))
      }
      metadataUnionActor ! MediaContribution(mediaFiles)
      persistenceManager ! esr
      esr.scanResult.mediaFiles foreach prepareHandlerForMedium
      sendAckIfPossible(esr)

    case _: EnhancedMediaScanResult => // no scan in progress or closing
      sender() ! ScanResultProcessed

    case av: AvailableMedia =>
      availableMedia = Some(av.media)
      checkAndHandleScanComplete()
      onConditionSatisfied()

    case CloseRequest if !scanInProgress =>
      sender() ! CloseAck(self)

    case CloseRequest if scanInProgress =>
      val actorsToClose = processorActors.values.toSet + persistenceManager
      onCloseRequest(self, actorsToClose, sender(), this, availableMedia.isDefined)
      pendingAck foreach (_ ! ScanResultProcessed)
      mediaInProgress = Set.empty
      pendingAck = Queue.empty

    case CloseComplete =>
      onCloseComplete()
      completeScanOperation()

    case GetMetadataFileInfo =>
      persistenceManager forward PersistentMetadataManagerActor.FetchMetadataFileInfo(self)

    case removeMsg: RemovePersistentMetadata =>
      if scanInProgress then
        sender() ! RemovePersistentMetadataResult(removeMsg, Set.empty)
      else
        persistenceManager forward removeMsg

  /**
    * Returns a flag whether a scan operation is currently in progress.
    *
    * @return a flag whether a scan is currently running
    */
  private def scanInProgress: Boolean = scanClient.isDefined

  /**
    * Sends an ACK for an incoming result if possible. If this is not possible,
    * the state is updated to reflect a pending ACK.
    *
    * @param esr the new result object
    */
  private def sendAckIfPossible(esr: EnhancedMediaScanResult): Unit =
    mediaInProgress ++= esr.scanResult.mediaFiles.keys
    if mediaInProgress.size <= config.metadataMediaBufferSize then
      sender() ! ScanResultProcessed
    else
      pendingAck = pendingAck enqueue sender()

  /**
    * Creates a metadata processing actor for the specified root path.
    *
    * @param root the root path
    * @return the new processing actor
    */
  private def createProcessorActor(root: Path): ActorRef =
    createChildActor(MetadataExtractionActor(self, ExtractorFactory,
      config.processorCount, config.processingTimeout))

  /**
    * Prepares a new scan operation. Initializes some internal state.
    *
    * @param client the client of the scan operation
    */
  private def initiateNewScan(client: ActorRef): Unit =
    log.info("Starting new scan.")
    metadataUnionActor ! UpdateOperationStarts(Some(self))
    persistenceManager ! ScanForMetadataFiles
    mediaMap = Map.empty
    completedMedia = Set(MediumID.UndefinedMediumID)
    scanClient = Some(client)

  /**
    * Prepares the handler object for a medium. If this medium is already known,
    * the existing handler is updated. Otherwise (which should be the default
    * case except for the undefined medium ID), a new handler object is
    * initialized.
    *
    * @param e an entry from the map of media files from a scan result object
    */
  private def prepareHandlerForMedium(e: (MediumID, List[FileData])): Unit =
    val mediumID = e._1
    val handler = mediaMap.getOrElse(mediumID, new MediumDataHandler(mediumID, converter))
    handler expectMediaFiles e._2
    mediaMap += mediumID -> handler

  /**
    * Handles a metadata processing result.
    *
    * @param mediumID the ID of the affected medium
    * @param result   the result to be handled
    * @return a flag whether this is a valid result
    */
  private def handleProcessingResult(mediumID: MediumID, result: MetadataProcessingResult):
  Boolean =
    val optHandler = mediaMap get mediumID
    optHandler.exists(processMetadataResult(mediumID, result, _))

  /**
    * Processes a metadata result that has been produced by a child actor. The
    * result is added to the responsible handler. If necessary, listeners are
    * notified.
    *
    * @param mediumID the ID of the affected medium
    * @param result   the processing result
    * @param handler  the handler for this medium
    * @return a flag whether this is a valid result
    */
  private def processMetadataResult(mediumID: MediumID, result: MetadataProcessingResult,
                                    handler: MediumDataHandler): Boolean =
    if handler.resultReceived(result) then
      if handler.isComplete then
        processPendingAck(mediumID)
      true
    else false

  /**
    * Checks whether an ACK has to be sent after the specified medium has been
    * completed.
    *
    * @param mediumID the completed medium
    */
  private def processPendingAck(mediumID: MediumID): Unit =
    completedMedia += mediumID
    mediaInProgress -= mediumID
    if mediaInProgress.size <= config.metadataMediaBufferSize then
      pendingAck.dequeueOption match
        case Some((actor, queue)) =>
          actor ! ScanResultProcessed
          pendingAck = queue
        case _ =>

  /**
    * Checks whether the scan for metadata is now complete. If this is the
    * case, the corresponding steps are done.
    */
  private def checkAndHandleScanComplete(): Unit =
    if allMediaProcessingResultsReceived then
      persistenceManager ! PersistentMetadataManagerActor.ScanCompleted
      scanClient foreach (_ ! MediaScanCompleted)
      completeScanOperation()

  /**
    * Performs all steps required to gracefully terminate the current scan
    * operation.
    */
  private def completeScanOperation(): Unit =
    scanClient = None
    availableMedia = None
    completedMedia = Set.empty
    processorActors.values.foreach(context.stop)
    processorActors = Map.empty
    metadataUnionActor ! UpdateOperationCompleted(Some(self))
    log.info("Scan complete.")

  /**
    * Returns a flag whether the processing results for all media have been
    * received. This is used to find out when a scan is complete.
    *
    * @return '''true''' if all results have been arrived; '''false'''
    *         otherwise
    */
  private def allMediaProcessingResultsReceived: Boolean =
    availableMedia exists (m => m.keySet subsetOf completedMedia)
