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

package de.oliver_heger.linedj.archive.media

import java.io.IOException
import java.nio.file.{Path, Paths}

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archivecommon.parser.MediumInfoParser
import de.oliver_heger.linedj.archivecommon.stream.AbstractStreamProcessingActor
import de.oliver_heger.linedj.extract.id3.model.ID3HeaderExtractor
import de.oliver_heger.linedj.io.CloseHandlerActor.CloseComplete
import de.oliver_heger.linedj.io._
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.shared.archive.union.{AddMedia, ArchiveComponentRemoved, MediaFileUriHandler, RemovedArchiveComponentProcessed}
import de.oliver_heger.linedj.utils.{ChildActorFactory, SchedulerSupport}

/**
 * Companion object.
 */
object MediaManagerActor {

  /**
   * A message processed by ''MediaManagerActor'' telling it to scan for media
   * in the specified root directory structures. This message tells the actor
   * which directory paths can contain media files. These paths are scanned,
   * and all files encountered (together with meta data about their media) are
   * collected. They comprise the library of audio sources that can be served
   * by this actor.
   *
   * @param roots a list with the root directories (as strings) that can
   *              contain media data
   */
  case class ScanMedia(roots: Iterable[String])

  /**
   * A message processed by ''MediaManagerActor'' telling it to check whether
   * there are reader actors with a timeout. This message is processed
   * periodically. This ensures that clients that terminated unexpectedly do
   * not cause hanging actor references.
   */
  case object CheckReaderTimeout

  /**
   * Constant for a prototype of a ''MediumFiles'' message for an unknown
   * medium.
   */
  private val UnknownMediumFiles = MediumFiles(null, Set.empty, existing = false)

  /**
   * Constant for a ''FileData'' referring to a non-existing file.
   */
  private val NonExistingFile = FileData(path = null, size = -1)

  private class MediaManagerActorImpl(config: MediaArchiveConfig, metaDataManager: ActorRef,
                                      mediaUnionActor: ActorRef)
    extends MediaManagerActor(config, metaDataManager, mediaUnionActor) with ChildActorFactory
      with SchedulerSupport with CloseSupport

  /**
   * Creates a ''Props'' object for creating new actor instances of this class.
   * Client code should always use the ''Props'' object returned by this
   * method; it ensures that all dependencies have been resolved.
   * @param config the configuration object
   * @param metaDataManager a reference to the meta data manager actor
   * @param mediaUnionActor reference to the media union actor
   * @return a ''Props'' object for creating actor instances
   */
  def apply(config: MediaArchiveConfig, metaDataManager: ActorRef,
            mediaUnionActor: ActorRef): Props =
    Props(classOf[MediaManagerActorImpl], config, metaDataManager, mediaUnionActor)

  /**
   * Transforms a path to a string URI.
   * @param p the path to be transformed
   * @return the resulting URI
   */
  private def pathToURI(p: Path): String = p.toString

  /**
   * Determines the path to a medium from the path to a medium description
   * file.
   * @param descPath the path to the description file
   * @return the resulting medium path
   */
  private def mediumPathFromDescription(descPath: Path): Path = descPath.getParent

  /**
   * Convenience method for returning the current system time.
   * @return the current time
   */
  private def now(): Long = System.currentTimeMillis()
}

/**
 * A specialized actor implementation for managing the media currently
 * available in the system.
 *
 * This actor can be triggered to scan an arbitrary number of directories for
 * media files. During this scan process medium description files are detected;
 * they are used to identify media and collect their content. (A medium can be
 * on a drive which can be replaced, e.g. a CD-ROM or a USB stick. It is also
 * possible that multiple media are stored under a root directory structure on
 * a hard disk.)
 *
 * After the scan operation is complete, the list with available media can be
 * queried. With this information, client applications can select the audio
 * data to be played. The content of specific media can be queried, and single
 * audio sources can be requested.
 *
 * @param config the configuration object
 * @param metaDataManager a reference to the meta data manager actor
 * @param mediaUnionActor a reference to the media union actor
 * @param downloadActorData internal helper object for managing reader actors
 */
class MediaManagerActor(config: MediaArchiveConfig, metaDataManager: ActorRef,
                        mediaUnionActor: ActorRef,
                        private[media] val downloadActorData: DownloadActorData) extends
Actor with ActorLogging {
  me: ChildActorFactory with SchedulerSupport with CloseSupport =>

  import MediaManagerActor._

  /** The extractor for ID3 information. */
  val id3Extractor = new ID3HeaderExtractor

  /** A helper object for calculating media IDs. */
  private[media] val idCalculator = new MediumIDCalculator

  /** A helper object for parsing medium description files. */
  private[media] val mediumInfoParser = new MediumInfoParser

  /** The actor for parsing media description files. */
  private var mediumInfoParserActor: ActorRef = _

  /** The actor for scanning media directory structures. */
  private var mediaScannerActor: ActorRef = _

  /** The map with the media currently available. */
  private var mediaMap = Map.empty[MediumID, MediumInfo]

  /**
   * A temporary map for storing media ID information. It is used while
   * constructing the information about the currently available media.
   */
  private val mediaIDData = collection.mutable.Map.empty[MediumID, MediumIDData]

  /**
   * A temporary map for storing media settings data extracted from media
   * description files. It is used while constructing the information about the
   * currently available media.
   */
  private val mediaSettingsData = collection.mutable.Map.empty[MediumID, MediumInfo]

  /**
   * A temporary set for storing the IDs of the media which are currently
   * processed. This is needed to associate the content of media description
   * files with the media they refer to.
   */
  private val currentMediumIDs = collection.mutable.Set.empty[MediumID]

  /**
   * A temporary map for storing information for the creation of enhanced scan
   * result objects. Such objects have to be passed to the meta data manager.
   * In order to construct them, all ''MediumIDData'' objects for the media
   * contained in a scan result have to be collected.
   */
  private val enhancedScanResultMapping = collection.mutable.Map.empty[MediaScanResult,
    List[MediumIDData]]

  /**
   * A map with information about the files contained in the currently
   * available media.
   */
  private val mediaFiles = collection.mutable.Map.empty[MediumID, Map[String, FileData]]

  /**
    * A list for storing messages temporarily that cannot be sent before
    * receiving a confirmation from the media union actor.
    */
  private var pendingMessages = List.empty[(ActorRef, Any)]

  /**
    * The current sequence number. The number is increased after a scan
    * operation. As all messages exchanged with collaboration actors contain
    * such numbers, it is possible to detect outdated messages.
    */
  private var sequenceNumber = 0

  /** The number of paths which have to be scanned in a current scan operation. */
  private var pathsToScan = 0

  /** The number of paths that have already been scanned. */
  private var pathsScanned = -1

  /** The number of available media.*/
  private var mediaCount = 0

  /** Cancellable for the periodic reader timeout check. */
  private var readerCheckCancellable: Option[Cancellable] = None

  /**
    * A flag to track whether a scan is the first one or a follow up scan. For
    * all scans except for the first the media union actor has to be notified
    * to remove the data of this archive component first.
    */
  private var firstScan = true

  /**
    * A flag whether a confirmation message for the remove archive component
    * message has already been received.
    */
  private var removeConfirmed = false

  /**
   * Creates a new instance of ''MediaManagerActor'' with a default reader
   * actor mapping.
    *
    * @param config the configuration object
   * @param metaDataManager a reference to the meta data manager actor
    * @param mediaUnionActor the media union actor
   */
  def this(config: MediaArchiveConfig, metaDataManager: ActorRef,
           mediaUnionActor: ActorRef) =
    this(config, metaDataManager, mediaUnionActor, new DownloadActorData)

  /**
   * The supervisor strategy used by this actor stops the affected child on
   * receiving an IO exception. This is used to detect failed scan operations.
   */
  override val supervisorStrategy: OneForOneStrategy = OneForOneStrategy() {
    case _: IOException => Stop
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    mediumInfoParserActor = createChildActor(Props(classOf[MediumInfoParserActor],
      mediumInfoParser, config.infoSizeLimit))
    mediaScannerActor = createChildActor(Props(classOf[MediaScannerActor],
      config.excludedFileExtensions))
    readerCheckCancellable = Some(scheduleMessage(config.readerCheckInitialDelay,
      config.readerCheckInterval, self, CheckReaderTimeout))
  }

  @throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    super.postStop()
    readerCheckCancellable foreach (_.cancel())
  }

  override def receive: Receive = {
    case ScanMedia(roots) =>
      processScanRequest(roots)

    case ScanAllMedia =>
      processScanRequest(config.mediaRootPaths)

    case MediaScannerActor.ScanPathResult(req, scanResult) if req.seqNo == sequenceNumber =>
      processScanResult(scanResult)

    case MediumIDCalculatorActor.CalculateMediumIDResult(req, idData)
      if req.seqNo == sequenceNumber =>
      processIDData(idData)
      stopSender()

    case MediumInfoParserActor.ParseMediumInfoResult(req, info)
      if req.seqNo == sequenceNumber =>
      storeSettingsData(info)

    case GetMediumFiles(mediumID) =>
      val optResponse = mediaFiles.get(mediumID) map
        (files => MediumFiles(mediumID, files.keySet, existing = true))
      sender ! optResponse.getOrElse(UnknownMediumFiles.copy(mediumID = mediumID))

    case request: MediumFileRequest =>
      processFileRequest(request)

    case t: Terminated =>
      handleActorTermination(t.actor)

    case CheckReaderTimeout =>
      checkForReaderActorTimeout()

    case ReaderActorAlive(reader, _) =>
      downloadActorData.updateTimestamp(reader, now())

    case CloseRequest =>
      onCloseRequest(self, List(metaDataManager), sender(), me)
      mediaScannerActor ! AbstractStreamProcessingActor.CancelStreams
      pendingMessages = Nil
      completeScanOperation()

    case CloseComplete =>
      onCloseComplete()

    case RemovedArchiveComponentProcessed(compID)
      if ArchiveComponentID == compID =>
      removeConfirmed = true
      pendingMessages.reverse foreach(t => t._1 ! t._2)
      pendingMessages = Nil
  }

  /**
   * Processes a ''MediumIDData'' object.
    *
    * @param idData the ''MediumIDData''
   */
  private def processIDData(idData: MediumIDData): Unit = {
    buildEnhancedScanResult(idData) foreach (sendOrCacheMessage(metaDataManager, _))
    if (idData.mediumID.mediumDescriptionPath.isEmpty) {
      appendMedium(idData, MediumInfoParserActor.undefinedMediumInfo)
    } else {
      mediaIDData += idData.mediumID -> idData
      createAndStoreMediumInfo(idData.mediumID)
    }
    mediaFiles += idData.mediumID -> idData.fileURIMapping
  }

  /**
   * Adds the specified ID data object to the temporary map for building up an
   * enhanced scan result. If the information is now complete, the enhanced
   * result is created and returned in the result option.
    *
    * @param idData the ''MediumIDData''
   * @return an option with the constructed enhanced scan result
   */
  private def buildEnhancedScanResult(idData: MediumIDData): Option[EnhancedMediaScanResult] = {
    val listIDData = idData :: enhancedScanResultMapping.getOrElse(idData.scanResult, Nil)
    enhancedScanResultMapping += (idData.scanResult -> listIDData)
    if (listIDData.size == idData.scanResult.mediaFiles.size)
      Some(createEnhancedScanResultFromIDData(listIDData))
    else None
  }

  /**
    * Constructs an ''EnhancedMediaScanResult'' object from all the
    * ''MediumIDData'' objects that belong to a scan result.
    *
    * @param listIDData the list with collected ''MediumIDData'' objects
    * @return the ''EnhancedMediaScanResult''
    */
  private def createEnhancedScanResultFromIDData(listIDData: List[MediumIDData]):
  EnhancedMediaScanResult = {
    val (checkSumMapping, uriMapping) = listIDData.foldLeft((Map.empty[MediumID, String], Map
      .empty[String, FileData])) { (maps, d) =>
      val chkMap = maps._1 + (d.mediumID -> d.checksum)
      val uriMap = maps._2 ++ d.fileURIMapping
      (chkMap, uriMap)
    }
    EnhancedMediaScanResult(listIDData.head.scanResult, checkSumMapping, uriMapping)
  }

  /**
    * Processes the request for a medium file.
    *
    * @param request the file request
   */
  private def processFileRequest(request: MediumFileRequest): Unit = {
    val response = fetchFileData(request) match {
      case Some(fileData) =>
        val downloadActor = createChildActor(Props(classOf[MediaFileDownloadActor],
          Paths get fileData.path, config.downloadChunkSize, !request.withMetaData))

        if (downloadActorData.findReadersForClient(sender()).isEmpty) {
          context watch sender()
        }
        downloadActorData.add(downloadActor, sender(), now())
        context watch downloadActor
        MediumFileResponse(request, Some(downloadActor), fileData.size)

      case None =>
        MediumFileResponse(request, None, NonExistingFile.size)
    }

    sender ! response
  }

  /**
   * Processes a request for scanning directory structures. If this request is
   * allowed in the current state of this actor, the scanning of the desired
   * directory structures is initiated.
    *
    * @param roots a sequence with the root directories to be scanned
   */
  private def processScanRequest(roots: Iterable[String]): Unit = {
    if (noScanInProgress) {
      sendScanStartMessages()
      mediaMap = Map.empty
      mediaCount = 0
      pathsToScan = roots.size
      pathsScanned = 0
      scanMediaRoots(roots)
    } else log.warning("Ignoring scan request for {}. Scan already in progress.", roots)
  }

  /**
    * Initializes flags and sends out messages when a new scan operation
    * starts.
    */
  private def sendScanStartMessages(): Unit = {
    removeConfirmed = firstScan
    if (firstScan) {
      firstScan = false
    } else {
      mediaUnionActor ! ArchiveComponentRemoved(ArchiveComponentID)
    }
    sendOrCacheMessage(metaDataManager, MediaScanStarts)
  }

  /**
   * Initiates scanning of the root directories with media files.
    *
    * @param roots a sequence with the root directories to be scanned
   */
  private def scanMediaRoots(roots: Iterable[String]): Unit = {
    log.info("Processing scan request for roots {}.", roots)
    roots foreach { root =>
      mediaScannerActor ! MediaScannerActor.ScanPath(Paths.get(root), sequenceNumber)
    }
    mediaDataAdded()
  }

  /**
   * Processes the result of a scan operation of a root directory. This method
   * triggers the calculation of media IDs and parsing of medium description
   * files.
    *
    * @param scanResult the data object with scan results
   */
  private def processScanResult(scanResult: MediaScanResult): Unit = {
    def triggerIDCalculation(mediumPath: Path, mediumID: MediumID, files: Seq[FileData]): Unit = {
      val idActor = createChildActor(Props(classOf[MediumIDCalculatorActor], idCalculator))
      idActor ! MediumIDCalculatorActor.CalculateMediumID(mediumPath, mediumID, scanResult, files,
        sequenceNumber)
    }

    currentMediumIDs ++= scanResult.mediaFiles.keySet
    scanResult.mediaFiles foreach { e =>
      e._1.mediumDescriptionPath match {
        case Some(path) =>
          val settingsPath = Paths get path
          mediumInfoParserActor ! MediumInfoParserActor.ParseMediumInfo(settingsPath, e._1,
            sequenceNumber)
          val mediumPath = mediumPathFromDescription(settingsPath)
          triggerIDCalculation(mediumPath, e._1, e._2)

        case _ =>
          triggerIDCalculation(scanResult.root,
            MediumID(pathToURI(scanResult.root), None, ArchiveComponentID), e._2)
      }
    }

    mediaCount += scanResult.mediaFiles.size
    incrementScannedPaths()
  }

  /**
   * Stores a ''MediumSettingsData'' object which has been created by a child
   * actor.
    *
    * @param data the data object to be stored
   */
  private def storeSettingsData(data: MediumInfo): Unit = {
    mediaSettingsData += data.mediumID -> data
    createAndStoreMediumInfo(data.mediumID)
  }

  /**
   * Checks whether all information for creating a ''MediumInfo'' object is
   * available for the specified medium URI. If so, the object is created and
   * stored in the global map.
    *
    * @param mediumID the ID of the affected medium
   */
  private def createAndStoreMediumInfo(mediumID: MediumID): Unit = {
    for {idData <- mediaIDData.get(mediumID)
         mediumInfo <- mediaSettingsData.get(mediumID)
    } {
      appendMedium(idData, mediumInfo)
    }
  }

  /**
   * Obtains the ''FileData'' object referred to by the given
   * ''MediumFileRequest''. The file is looked up in the data structures managed by
   * this actor. If it cannot be found, result is ''None''.
    *
    * @param request the request identifying the desired file
   * @return an option with the ''FileData''
   */
  private def fetchFileData(request: MediumFileRequest): Option[FileData] = {
    MediaFileUriHandler.resolveUri(request.mediumID, request.uri, mediaFiles)
  }

  /**
   * Stops the sending actor. This method is called when a result message from
   * a temporary child actor was received. This actor can now be stopped.
   */
  private def stopSender(): Unit = {
    context stop sender()
  }

  /**
   * Checks whether the information about available media is now complete.
    *
    * @return a flag whether all information is now complete
   */
  private def mediaInformationComplete: Boolean =
    pathsScanned >= pathsToScan && mediaMap.size >= mediaCount

  /**
   * Notifies this object that new media information has been added. If this
   * information is now complete, the scan operation can be terminated, and
   * pending requests can be handled.
    *
    * @return a flag whether the data about media is now complete
   */
  private def mediaDataAdded(): Boolean = {
    if (mediaInformationComplete) {
      sendOrCacheMessage(metaDataManager, AvailableMedia(mediaMap))
      sendOrCacheMessage(mediaUnionActor,
        AddMedia(mediaMap, ArchiveComponentID, None))
      completeScanOperation()
      true
    } else false
  }

  /**
   * Completes a scan operation. Temporary fields are reset.
   */
  private def completeScanOperation(): Unit = {
    pathsToScan = -1
    pathsScanned = -1
    mediaIDData.clear()
    mediaSettingsData.clear()
    currentMediumIDs.clear()
    enhancedScanResultMapping.clear()
    sequenceNumber += 1
  }

  /**
   * Appends another entry to the map with media data. If the data is now
   * complete, the scan operation is terminated.
    *
    * @param idData the ID data object for the medium
   * @param info the medium info object
   * @return a flag whether the data about media is now complete
   */
  private def appendMedium(idData: MediumIDData, info: MediumInfo): Boolean = {
    mediaMap += idData.mediumID -> info.copy(checksum = idData.checksum)
    mediaDataAdded()
  }

  /**
   * Increments the number of paths that have been scanned. This method also
   * checks whether this was the last pending path.
   */
  private def incrementScannedPaths(): Unit = {
    pathsScanned += 1
    mediaDataAdded()
  }

  /**
   * Checks that currently no scan is in progress. This method is used to
   * avoid the processing of multiple scan requests in parallel.
    *
    * @return a flag whether currently no scan request is in progress
   */
  private def noScanInProgress: Boolean = pathsScanned < 0

  /**
    * Sends a message to a target actor if this is already possible. Some
    * messages cannot be sent before the media union actor has acknowledged
    * that it has removed the data of this archive component. If this is not
    * yet the case, the message is cached to be sent out later.
    *
    * @param target the target actor
    * @param msg    the message to be sent
    */
  private def sendOrCacheMessage(target: ActorRef, msg: Any): Unit = {
    if (removeConfirmed) target ! msg
    else pendingMessages = (target, msg) :: pendingMessages
  }

  /**
    * Handles an actor terminated message. We have to determine which type of
    * actor is affected by this message. If it is a reader actor, then a
    * download operation is finished, and some cleanup has to be done. It can
    * also be the client actor of a read operation; then all reader actors
    * related to this client can be canceled.
    *
    * @param actor the affected actor
    */
  private def handleActorTermination(actor: ActorRef): Unit = {
    if (downloadActorData hasActor actor) {
      handleReaderActorTermination(actor)
    } else {
      val readerActors = downloadActorData.findReadersForClient(actor)
      readerActors foreach context.stop
    }
  }

  /**
    * Handles the termination of a reader actor. We can stop watching
    * the client actor if there are no more pending read operations on behalf
    * of it.
    *
    * @param actor the terminated actor
    */
  private def handleReaderActorTermination(actor: ActorRef): Unit = {
    log.info("Removing terminated reader actor from mapping.")
    val optClient = downloadActorData remove actor
    optClient foreach { c =>
      if (downloadActorData.findReadersForClient(c).isEmpty) {
        context unwatch c
      }
    }
  }

  /**
   * Checks all currently active reader actors for timeouts. This method is
   * called periodically. It checks whether there are actors which have not
   * been updated during a configurable interval. This typically indicates a
   * crash of the corresponding client.
   */
  private def checkForReaderActorTimeout(): Unit = {
    downloadActorData.findTimeouts(now(), config.readerTimeout) foreach
      stopReaderActor
  }

  /**
   * Stops a reader actor when the timeout was reached.
    *
    * @param actor the actor to be stopped
   */
  private def stopReaderActor(actor: ActorRef): Unit = {
    context stop actor
    log.warning("Reader actor {} stopped because of timeout!", actor.path)
  }

}
