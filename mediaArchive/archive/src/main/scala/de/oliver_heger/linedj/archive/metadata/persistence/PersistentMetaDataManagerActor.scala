/*
 * Copyright 2015-2019 The Developers Team.
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

import java.nio.file.Path

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.stream.ActorMaterializer
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.media.EnhancedMediaScanResult
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetaDataWriterActor.ProcessMedium
import de.oliver_heger.linedj.archive.metadata.{ScanForMetaDataFiles, UnresolvedMetaDataFiles}
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.{GetMetaDataFileInfo, MetaDataFileInfo, RemovePersistentMetaData, RemovePersistentMetaDataResult}
import de.oliver_heger.linedj.shared.archive.union.MetaDataProcessingSuccess
import de.oliver_heger.linedj.utils.ChildActorFactory

import scala.annotation.tailrec

object PersistentMetaDataManagerActor {
  /** File extension for meta data files. */
  val MetaDataFileExtension = ".mdt"

  /**
    * Constant for a ''MetaDataFileInfo'' object containing no data. This
    * object is returned if meta data information is requested before a scan
    * for meta data files has been triggered.
    */
  val EmptyMetaDataFileInfo = MetaDataFileInfo(Map.empty, Set.empty)

  /**
    * A message expected by [[PersistentMetaDataManagerActor]] at the end of
    * a scan operation. This allows the actor to do some post processing after
    * a scan.
    */
  case object ScanCompleted

  /**
    * A message sent by [[PersistentMetaDataManagerActor]] to itself when the
    * map with meta data files is available.
    *
    * @param files the map with meta data files
    */
  private case class MetaDataFileResult(files: Map[String, Path])

  /**
    * An internally used data class that stores information about media that
    * are currently processed by this actor.
    *
    * @param request       the request for reading the meta data file
    * @param scanResult    the associated scan result
    * @param listenerActor the actor to be notified for results
    * @param resolvedFiles a set with the files that could be resolved
    * @param readerActor   the actor that reads the file for this medium
    */
  private case class MediumData(request: PersistentMetaDataReaderActor.ReadMetaDataFile,
                                scanResult: EnhancedMediaScanResult,
                                listenerActor: ActorRef, resolvedFiles: Set[String] = Set.empty,
                                readerActor: ActorRef = null) {
    /**
      * Convenience method that returns the ID of the associated medium.
      *
      * @return the medium ID
      */
    def mediumID: MediumID = request.mediumID

    /**
      * Assigns the specified reader actor to the represented medium.
      *
      * @param reader the reader actor
      * @return the updated instance
      */
    def assignReaderActor(reader: ActorRef): MediumData =
      copy(readerActor = reader)

    /**
      * Updates this instance with a processing result that became available.
      * The paths of resolved files are stored so that it is later possible to
      * determine unresolved files.
      *
      * @param result the processing result
      * @return the updated instance
      */
    def updateResolvedFiles(result: MetaDataProcessingSuccess): MediumData =
      copy(resolvedFiles = resolvedFiles + result.path.toString)

    /**
      * Creates an object with information about meta data files that have not
      * been resolved. If there are no unresolved files, result is ''None''.
      *
      * @return the object about unresolved meta data files
      */
    def unresolvedFiles(): Option[UnresolvedMetaDataFiles] = {
      val unresolvedFiles = scanResult.scanResult.mediaFiles(mediumID) filterNot (d => resolvedFiles
        .contains(d.path))
      if (unresolvedFiles.isEmpty) None
      else Some(UnresolvedMetaDataFiles(mediumID = mediumID, result = scanResult,
        files = unresolvedFiles))
    }

    /**
      * Returns the number of files on the represented medium which could be
      * resolved.
      * @return the number of resolved files
      */
    def resolvedFilesCount: Int = resolvedFiles.size
  }

  private class PersistentMetaDataManagerActorImpl(config: MediaArchiveConfig,
                                                   metaDataUnionActor: ActorRef,
                                                   fileScanner: PersistentMetaDataFileScanner)
    extends PersistentMetaDataManagerActor(config, metaDataUnionActor, fileScanner)
      with ChildActorFactory

  /**
    * Returns a ''Props'' object for creating an instance of this actor class.
    *
    * @param config             the configuration
    * @param metaDataUnionActor the meta data union actor
    * @return creation properties for a new actor instance
    */
  def apply(config: MediaArchiveConfig, metaDataUnionActor: ActorRef): Props =
    Props(classOf[PersistentMetaDataManagerActorImpl], config, metaDataUnionActor,
      new PersistentMetaDataFileScanner)
}

/**
  * An actor for managing files with media meta data.
  *
  * An instance of this class is responsible for managing persistent meta data
  * files. On startup, this actor scans the meta directory for ''*.mdt'' files
  * associated with the media available. When messages about the media files
  * available are received, it checks whether corresponding meta data files
  * exist. If so, [[PersistentMetaDataReaderActor]] instances are created to
  * read these files. The results of these read operations are then passed
  * back to the calling actor (which is typically the meta data manager actor).
  *
  * Meta data for songs added to the music library is not available initially.
  * Therefore, this actor checks whether persistent meta data is available for
  * a given medium and if it is complete. If this is not the case, the data
  * should be updated. This task is delegated to a specialized child actor.
  * The goal is to generate persistent meta data automatically by storing the
  * information extracted from media files.
  *
  * @param config             the configuration
  * @param metaDataUnionActor reference to the meta data union actor
  * @param fileScanner        the scanner for meta data files
  */
class PersistentMetaDataManagerActor(config: MediaArchiveConfig,
                                     metaDataUnionActor: ActorRef,
                                     private[persistence] val fileScanner:
                                     PersistentMetaDataFileScanner)
  extends Actor with ActorLogging {
  this: ChildActorFactory =>

  import PersistentMetaDataManagerActor._

  /** The object to materialize streams. */
  private implicit val mat: ActorMaterializer = ActorMaterializer()

  /**
    * Stores information about meta data files available. The data is loaded
    * when the actor is started; so it may no be available immediately.
    */
  private var optMetaDataFiles: Option[Map[String, Path]] = None

  /**
    * A list with scan results sent to this actor. These results can only be
    * processed after information about meta data files is available.
    */
  private var pendingScanResults = List.empty[EnhancedMediaScanResult]

  /**
    * A list with requests for reading meta data files that are waiting to be
    * processed by a reader actor.
    */
  private var pendingReadRequests = List.empty[MediumData]

  /**
    * A map storing information about the media whose data files are currently
    * read. This map stores sufficient information to send notifications about
    * results correctly.
    */
  private var mediaInProgress = Map.empty[MediumID, MediumData]

  /**
    * A mapping for medium IDs to checksum data. This map is filled from the
    * received enhanced scan results. It is used to find out for which media
    * meta data files exist.
    */
  private var checksumMapping = Map.empty[MediumID, String]

  /**
    * The child actor for writing meta data for media with incomplete
    * information.
    */
  private var writerActor: ActorRef = _

  /** The child actor for remove meta data files operation. */
  private var removeActor: ActorRef = _

  /** The child actor for writing a ToC for the archive. */
  private var tocWriterActor: ActorRef = _

  /** The current number of active reader actors. */
  private var activeReaderActors = 0

  /** Stores information about a pending close request. */
  private var closeRequest: Option[ActorRef] = None

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    writerActor = createChildActor(Props(classOf[PersistentMetaDataWriterActor],
      config.metaDataPersistenceWriteBlockSize))
    removeActor = createChildActor(MetaDataFileRemoveActor())
    tocWriterActor = createChildActor(Props[ArchiveToCWriterActor])
  }

  override def receive: Receive = {
    case ScanForMetaDataFiles =>
      triggerMetaDataFileScan()
      checksumMapping = Map.empty

    case MetaDataFileResult(files) =>
      optMetaDataFiles = Some(files)
      processPendingScanResults(pendingScanResults)

    case res: EnhancedMediaScanResult if closeRequest.isEmpty =>
      processPendingScanResults(res :: pendingScanResults)
      checksumMapping = checksumMapping ++ res.checksumMapping

    case result: MetaDataProcessingSuccess =>
      val optMediaData = mediaInProgress get result.mediumID
      optMediaData foreach { d =>
        d.listenerActor ! result
        mediaInProgress = mediaInProgress.updated(d.mediumID, d updateResolvedFiles result)
      }

    case PersistentMetaDataWriterActor.MetaDataWritten(process,
    success) if sender() == writerActor =>
      optMetaDataFiles = optMetaDataFiles map {
        updateMetaDataFiles(_, checksumMapping, process)(if(success) addMetaDataFile
        else removeMetaDataFile)
      }

    case GetMetaDataFileInfo =>
      sender ! fetchCurrentMetaFileInfo()

    case req: RemovePersistentMetaData =>
      val (target, msg) = generateRemoveRequestResponse(req, optMetaDataFiles, sender())
      target ! msg

    case MetaDataFileRemoveActor.RemoveMetaDataFilesResult(request, deleted) =>
      request.client ! RemovePersistentMetaDataResult(RemovePersistentMetaData(request.fileIDs),
        deleted)
      optMetaDataFiles = optMetaDataFiles map { files =>
        files.filterNot(t => deleted.contains(t._1))
      }

    case ScanCompleted =>
      if (config.contentTableConfig.contentFile.isDefined) {
        val info = fetchCurrentMetaFileInfo()
        tocWriterActor ! ArchiveToCWriterActor.WriteToC(config.contentTableConfig,
          info.metaDataFiles.toList)
      }

    case Terminated(reader) =>
      activeReaderActors -= 1
      closeRequest match {
        case Some(rec) =>
          checkAndSendCloseAck(rec)
        case None => startReaderActors()
      }

      val optMediumData = mediaInProgress.values find (_.readerActor == reader)
      optMediumData foreach { d =>
        val unresolvedFiles = d.unresolvedFiles()
        unresolvedFiles foreach (processUnresolvedFiles(_, d.listenerActor, d.resolvedFilesCount))
        mediaInProgress = mediaInProgress - d.mediumID
      }

    case CloseRequest =>
      closeRequest = Some(sender())
      mediaInProgress = Map.empty
      pendingReadRequests = Nil
      checkAndSendCloseAck(sender())
  }

  /**
    * Invokes the file scanner to start the scan for meta data files. When
    * the future with the scan result completes, the result is sent as a
    * message to this actor.
    */
  private def triggerMetaDataFileScan(): Unit = {
    log.info("Scanning {} for meta data files.", config.metaDataPersistencePath)
    import context.dispatcher
    fileScanner.scanForMetaDataFiles(config.metaDataPersistencePath)
      .recover {
        case e: Exception =>
          log.error(e, "Could not read meta data files!")
          Map.empty[String, Path]
      } map (m => MetaDataFileResult(m)) foreach (self ! _)
  }

  /**
    * Generates the response to send in reaction on a request to remove meta
    * data files. If the information about these files is not yet available,
    * the caller is sent a result immediately (indicating that no files have
    * been deleted); otherwise, the request is passed to the remove actor.
    *
    * @param req      the request to remove files
    * @param fileData the current meta data file information
    * @param caller   the calling actor
    * @return a tuple with the target actor and the message to send
    */
  private def generateRemoveRequestResponse(req: RemovePersistentMetaData,
                                            fileData: Option[Map[String, Path]],
                                            caller: ActorRef): (ActorRef, Any) =
  fileData match {
    case Some(files) =>
      (removeActor, MetaDataFileRemoveActor.RemoveMetaDataFiles(req.checksumSet,
        files, caller))
    case None =>
      (caller, RemovePersistentMetaDataResult(req, Set.empty))
  }

  /**
    * Creates a child actor for reading a meta data file.
    *
    * @return the child reader actor
    */
  private def createChildReaderActor(): ActorRef =
    createChildActor(PersistentMetaDataReaderActor(self, config
      .metaDataPersistenceChunkSize))

  /**
    * Creates a child actor for reading a meta data file and sends it a read
    * request.
    *
    * @param request the request for the child actor
    * @return the child reader actor
    */
  private def createAndStartChildReaderActor(request: PersistentMetaDataReaderActor
  .ReadMetaDataFile): ActorRef = {
    val reader = createChildReaderActor()
    reader ! request
    context watch reader
    reader
  }

  /**
    * Processes the specified pending scan results. The results are grouped
    * using the grouping functions; then the groups are handled accordingly.
    *
    * @param pendingResults the pending results to be processed
    */
  private def processPendingScanResults(pendingResults: List[EnhancedMediaScanResult]): Unit = {
    val (pending, unresolved, requests) = groupPendingScanResults(optMetaDataFiles,
      pendingResults)
    unresolved foreach (processUnresolvedFiles(_, sender(), 0))
    pendingReadRequests = requests ::: pendingReadRequests
    startReaderActors()
    pendingScanResults = pending
  }

  /**
    * Processes an ''UnresolvedMetaDataFiles'' message for a medium for which
    * no meta data file could be found.
    *
    * @param u                the message to be processed
    * @param metaManagerActor the meta data manager actor
    * @param resolved         the number of unresolved files
    */
  private def processUnresolvedFiles(u: UnresolvedMetaDataFiles, metaManagerActor:
  ActorRef, resolved: Int): Unit = {
    metaManagerActor ! u
    writerActor ! createProcessMediumMessage(u, resolved)
  }

  /**
    * Creates a ''ProcessMedium'' message based on the specified parameters.
    *
    * @param u                the ''UnresolvedMetaDataFiles'' message
    * @param resolved         the number of unresolved files
    * @return the message
    */
  private def createProcessMediumMessage(u: UnresolvedMetaDataFiles, resolved: Int):
  ProcessMedium =
    PersistentMetaDataWriterActor.ProcessMedium(mediumID = u.mediumID,
      target = generateMetaDataPath(u), metaDataManager = metaDataUnionActor, uriPathMapping = u
        .result.fileUriMapping, resolvedSize = resolved)

  /**
    * Generates the path for a meta data file based on the specified
    * ''UnresolvedMetaDataFiles'' object.
    *
    * @param u the object describing unresolved files on a medium
    * @return the path for the corresponding meta data file
    */
  private def generateMetaDataPath(u: UnresolvedMetaDataFiles): Path =
    generateMetaDataPath(u.result.checksumMapping(u.mediumID))

  /**
    * Generates the path for a meta data file based on the specified checksum.
    *
    * @param checksum the checksum
    * @return the path for the corresponding meta data file
    */
  private def generateMetaDataPath(checksum: String): Path =
  config.metaDataPersistencePath.resolve(checksum + MetaDataFileExtension)

  /**
    * Starts as many reader actors for meta data files as possible. For each
    * medium request not yet in progress an actor is started until the maximum
    * number of parallel read actors is reached.
    */
  private def startReaderActors(): Unit = {
    val (requests, inProgress, count) = updateMediaInProgress(pendingReadRequests,
      mediaInProgress, activeReaderActors)
    mediaInProgress = inProgress
    pendingReadRequests = requests
    activeReaderActors = count
  }

  /**
    * Groups a list with pending scan results. If meta data files are
    * already available, the media in all scan results are grouped whether a
    * corresponding meta data file exists for them. The resulting lists can be
    * used for further processing.
    *
    * @param optMetaDataFiles an option with meta data files
    * @param scanResults      a list with pending scan results
    * @return the updated list of pending results and lists for further
    *         processing of affected media
    */
  private def groupPendingScanResults(optMetaDataFiles: Option[Map[String, Path]],
                                      scanResults: List[EnhancedMediaScanResult]):
  (List[EnhancedMediaScanResult], List[UnresolvedMetaDataFiles], List[MediumData]) =
    optMetaDataFiles match {
      case Some(map) =>
        val resGroups = scanResults.map(groupMedia(_, map)).unzip
        (Nil, resGroups._1.flatten, resGroups._2.flatten)
      case None =>
        (scanResults, Nil, Nil)
    }

  /**
    * Groups all media in the specified scan result whether a meta data file
    * for them exists or not. The resulting tuple of lists can be used to
    * further process the media.
    *
    * @param res       the current scan result
    * @param dataFiles the map with available data files
    * @return a tuple with sequences about unresolved media and read requests
    */
  private def groupMedia(res: EnhancedMediaScanResult, dataFiles: Map[String, Path]):
  (List[UnresolvedMetaDataFiles], List[MediumData]) = {
    res.scanResult.mediaFiles.foldLeft(
      (List.empty[UnresolvedMetaDataFiles], List.empty[MediumData])) {
      (t, e) => groupMedium(e._1, dataFiles, res, t._1, t._2)
    }
  }

  /**
    * Checks whether for the specified medium a data file exists. By that a
    * grouping of media can be made. To which group a medium is added
    * determines the way it is handled.
    *
    * @param mediumID     the ID of the medium
    * @param dataFiles    the map with available data files
    * @param res          the current scan result
    * @param unresolved   a sequence with unresolved media
    * @param readRequests a sequence with read requests for known media
    * @return a tuple with the updated sequences
    */
  private def groupMedium(mediumID: MediumID, dataFiles: Map[String, Path],
                          res: EnhancedMediaScanResult,
                          unresolved: List[UnresolvedMetaDataFiles],
                          readRequests: List[MediumData]):
  (List[UnresolvedMetaDataFiles], List[MediumData]) =
    dataFiles get res.checksumMapping(mediumID) match {
      case Some(path) =>
        (unresolved, MediumData(request = PersistentMetaDataReaderActor.ReadMetaDataFile(path,
          mediumID),
          scanResult = res, listenerActor = sender()) :: readRequests)
      case None =>
        (UnresolvedMetaDataFiles(mediumID, res.scanResult.mediaFiles(mediumID), res) ::
          unresolved, readRequests)
    }

  /**
    * Updates information about currently processed media. This method is
    * called when new scan data objects arrive or a medium is completely
    * processed. In this case, new read operations may be started. The
    * corresponding data is returned by this method.
    *
    * @param requests    pending read requests
    * @param inProgress  the map with currently processed media
    * @param readerCount the current number of active reader actors
    * @return a tuple with updated information
    */
  @tailrec private def updateMediaInProgress(requests: List[MediumData],
                                             inProgress: Map[MediumID, MediumData], readerCount:
                                             Int): (List[MediumData], Map[MediumID, MediumData],
    Int) =
    requests match {
      case h :: t if readerCount < config.metaDataPersistenceParallelCount =>
        val reader = createAndStartChildReaderActor(h.request)
        updateMediaInProgress(t, inProgress + (h.request.mediumID -> h.assignReaderActor(reader))
          , readerCount + 1)
      case _ => (requests, inProgress, readerCount)
    }

  /**
    * Returns an object with information about the meta data files managed by
    * this actor.
    *
    * @return information about meta data files
    */
  private def fetchCurrentMetaFileInfo(): MetaDataFileInfo =
    optMetaDataFiles map (createMetaDataFileInfo(_,
      checksumMapping)) getOrElse EmptyMetaDataFileInfo

  /**
    * Creates an object with information about meta data files.
    *
    * @param metaDataFiles the map with meta data files
    * @param checkMap      the checksum mapping
    * @return the ''MetaDataFileInfo'' object
    */
  private def createMetaDataFileInfo(metaDataFiles: Map[String, Path],
                                     checkMap: Map[MediumID, String]):
  MetaDataFileInfo = {
    val assignedFiles = checkMap filter (e => metaDataFiles contains e._2)
    val usedFiles = assignedFiles.values.toSet
    val unusedFiles = metaDataFiles.keySet diff usedFiles
    MetaDataFileInfo(assignedFiles, unusedFiles)
  }

  /**
    * Updates the map with meta data files. This function checks whether the
    * specified ''ProcessMedium'' object refers to a valid medium. If so, it
    * delegates to the passed in update function to do the actual update. The
    * update function is passed the original map with meta data files, the
    * checksum affected by the operation, and the ''ProcessMedium'' object.
    *
    * @param metaDataFiles the current map with meta data files
    * @param checkMap      the checksum mapping
    * @param process       the current ''ProcessMedium'' object
    * @param f             the update function
    * @return the updated map
    */
  private def updateMetaDataFiles(metaDataFiles: Map[String, Path],
                                  checkMap: Map[MediumID, String], process: ProcessMedium)
                                 (f: (Map[String, Path], String,
                                   ProcessMedium) => Map[String, Path]): Map[String, Path] =
  checkMap get process.mediumID match {
    case Some(cs) => f(metaDataFiles, cs, process)
    case None => metaDataFiles
  }

  /**
    * Adds a newly written meta data file to the mapping of meta data files.
    *
    * @param metaDataFiles the meta data file mapping
    * @param checksum      the checksum of the affected file
    * @param process       the ''ProcessMedium'' message from the writer actor
    * @return the updated meta data file mapping
    */
  private def addMetaDataFile(metaDataFiles: Map[String, Path], checksum: String,
                              process: ProcessMedium): Map[String, Path] =
  metaDataFiles + (checksum -> generateMetaDataPath(checksum))

  /**
    * Removes a file from the map with meta data files after a failed write
    * operation.
    *
    * @param metaDataFiles the meta data file mapping
    * @param checksum      the checksum of the affected file
    * @param process       the ''ProcessMedium'' message from the writer actor
    * @return the updated meta data file mapping
    */
  private def removeMetaDataFile(metaDataFiles: Map[String, Path], checksum: String,
                                 process: ProcessMedium): Map[String, Path] =
  metaDataFiles - checksum

  /**
    * Checks whether a close request can be answered. If so, the Ack message
    * is sent to the given receiver, and the internal state is updated.
    *
    * @param receiver the receiver
    */
  private def checkAndSendCloseAck(receiver: ActorRef): Unit = {
    if (activeReaderActors == 0) {
      receiver ! CloseAck(self)
      closeRequest = None
    }
  }
}
