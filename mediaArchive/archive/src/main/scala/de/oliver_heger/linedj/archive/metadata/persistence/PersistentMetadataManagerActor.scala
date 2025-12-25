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

import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.media.{EnhancedMediaScanResult, PathUriConverter}
import de.oliver_heger.linedj.archive.metadata.{ScanForMetadataFiles, UnresolvedMetadataFiles}
import de.oliver_heger.linedj.archivecommon.parser.MetadataParser
import de.oliver_heger.linedj.io.stream.{FilterInstanceOfStage, ListSeparatorStage}
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.shared.actors.ChildActorFactory
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.Checksums.MediumChecksum
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MetadataFileInfo, RemovePersistentMetadata, RemovePersistentMetadataResult}
import de.oliver_heger.linedj.shared.archive.union.{MetadataProcessingResult, MetadataProcessingSuccess}
import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Terminated}
import org.apache.pekko.stream.scaladsl.{FileIO, Flow, Keep, Sink}

import java.nio.file.Path
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.{Failure, Success}

object PersistentMetadataManagerActor:
  /** File extension for metadata files. */
  val MetadataFileExtension = ".mdt"

  /**
    * Constant for a ''MetaDataFileInfo'' object containing no data. This
    * object is returned if metadata information is requested before a scan
    * for metadata files has been triggered.
    */
  val EmptyMetadataFileInfo: MetadataFileInfo = MetadataFileInfo(Map.empty, Set.empty, None)

  /**
    * A message expected by [[PersistentMetadataManagerActor]] at the end of
    * a scan operation. This allows the actor to do some post-processing after
    * a scan.
    */
  case object ScanCompleted

  /**
    * A message expected by [[PersistentMetadataManagerActor]] when an 
    * operation to extract the metadata for a medium is completed. Based on the
    * success status, the actor updates the list of metadata files.
    *
    * @param mediumID the ID of the affected medium
    * @param success  flag indicating the success status of the operation
    */
  case class MetadataExtractionCompleted(mediumID: MediumID, success: Boolean)

  /**
    * A message processed by [[PersistentMetadataManagerActor]] that requests
    * an object with information about metadata files.
    *
    * The actor returns a [[MetadataFileInfo]] object that references the
    * passed in controller actor.
    *
    * @param controller the controller actor
    */
  case class FetchMetadataFileInfo(controller: ActorRef)

  /**
    * A message sent by [[PersistentMetadataManagerActor]] to itself when the
    * map with metadata files is available.
    *
    * @param files the map with metadata files
    */
  private case class MetadataFileResult(files: Map[MediumChecksum, Path])

  /**
    * A message sent by [[PersistentMetadataManagerActor]] to itself after the
    * future returned by the ToC writer has completed. The result is then
    * logged.
    *
    * @param optTocPath   the optional path to the file that was written
    * @param optException an optional exception in case of a failure
    */
  private case class TocWriterResult(optTocPath: Option[Path],
                                     optException: Option[Throwable])

  /**
    * An internally used data class that stores information about media that
    * are currently processed by this actor.
    *
    * @param request          the request for reading the metadata file
    * @param scanResult       the associated scan result
    * @param listenerActor    the actor to be notified for results
    * @param resolvedMetadata a set with metadata for the files that could be
    *                         resolved
    * @param readerActor      the actor that reads the file for this medium
    */
  private case class MediumData(request: PersistentMetadataReaderActor.ReadMetadataFile,
                                scanResult: EnhancedMediaScanResult,
                                listenerActor: ActorRef,
                                resolvedMetadata: Set[MetadataProcessingSuccess] = Set.empty,
                                readerActor: ActorRef = null):
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
    def updateResolvedFiles(result: MetadataProcessingSuccess): MediumData =
      copy(resolvedMetadata = resolvedMetadata + result)

    /**
      * Creates an object with information about metadata files that have not
      * been resolved. If there are no unresolved files, result is ''None''.
      *
      * @param converter the ''PathUriConverter''
      * @return the object about unresolved metadata files
      */
    def unresolvedFiles(converter: PathUriConverter): Option[UnresolvedMetadataFiles] =
      val resolvedUris = resolvedMetadata.map(_.uri.uri)
      val unresolvedFiles = scanResult.scanResult.mediaFiles(mediumID) filterNot : d =>
        resolvedUris.contains(converter.pathToUri(d.path).uri)
      if unresolvedFiles.isEmpty then None
      else Some(
        UnresolvedMetadataFiles(
          mediumID = mediumID,
          result = scanResult,
          files = unresolvedFiles,
          resolvedFiles = resolvedMetadata.toList,
          Sink.ignore // The correct Sink is constructed later.
        )
      )

    /**
      * Returns the number of files on the represented medium which could be
      * resolved.
      *
      * @return the number of resolved files
      */
    def resolvedFilesCount: Int = resolvedMetadata.size

  private class PersistentMetadataManagerActorImpl(config: MediaArchiveConfig,
                                                   metadataUnionActor: ActorRef,
                                                   fileScanner: PersistentMetadataFileScanner,
                                                   converter: PathUriConverter,
                                                   tocWriter: ArchiveTocWriter)
    extends PersistentMetadataManagerActor(config, metadataUnionActor, fileScanner, converter, tocWriter)
      with ChildActorFactory

  /**
    * Returns a ''Props'' object for creating an instance of this actor class.
    *
    * @param config             the configuration
    * @param metadataUnionActor the metadata union actor
    * @param converter          the ''PathUriConverter''
    * @param tocWriter          the object to generate a ToC for the archive
    * @return creation properties for a new actor instance
    */
  def apply(config: MediaArchiveConfig,
            metadataUnionActor: ActorRef,
            converter: PathUriConverter,
            tocWriter: ArchiveTocWriter = ArchiveTocWriter()): Props =
    Props(
      classOf[PersistentMetadataManagerActorImpl],
      config,
      metadataUnionActor,
      new PersistentMetadataFileScanner,
      converter,
      tocWriter
    )

/**
  * An actor for managing files with media metadata.
  *
  * An instance of this class is responsible for managing persistent metadata
  * files. On startup, this actor scans the meta directory for ''*.mdt'' files
  * associated with the media available. When messages about the media files
  * available are received, it checks whether corresponding metadata files
  * exist. If so, [[PersistentMetadataReaderActor]] instances are created to
  * read these files. The results of these read operations are then passed
  * back to the calling actor (which is typically the metadata manager actor).
  *
  * metadata for songs added to the music library is not available initially.
  * Therefore, this actor checks whether persistent metadata is available for
  * a given medium and if it is complete. If this is not the case, the data
  * should be updated. This task is delegated to a specialized child actor.
  * The goal is to generate persistent metadata automatically by storing the
  * information extracted from media files.
  *
  * @param config             the configuration
  * @param metadataUnionActor reference to the metadata union actor
  * @param fileScanner        the scanner for metadata files
  * @param converter          the ''PathUriConverter''
  * @param tocWriter          the object to write the archive's ToC
  */
class PersistentMetadataManagerActor(config: MediaArchiveConfig,
                                     metadataUnionActor: ActorRef,
                                     private[persistence] val fileScanner: PersistentMetadataFileScanner,
                                     converter: PathUriConverter,
                                     tocWriter: ArchiveTocWriter)
  extends Actor with ActorLogging:
  this: ChildActorFactory =>

  import PersistentMetadataManagerActor.*

  /**
    * Stores information about metadata files available. The data is loaded
    * when the actor is started; so it may not be available immediately.
    */
  private var optMetadataFiles: Option[Map[MediumChecksum, Path]] = None

  /**
    * A list with scan results sent to this actor. These results can only be
    * processed after information about metadata files is available.
    */
  private var pendingScanResults = List.empty[EnhancedMediaScanResult]

  /**
    * A list with requests for reading metadata files that are waiting to be
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
    * metadata files exist.
    */
  private var checksumMapping = Map.empty[MediumID, MediumChecksum]

  /**
    * Stores the IDs of media with incomplete metadata. For such media, a
    * metadata extraction process is started.
    */
  private var mediaMissingMetadata = Set.empty[MediumID]

  /**
    * Stores the IDs of media for which updated metadata information is now
    * available. For those media, the entry in the ToC must be updated.
    */
  private var modifiedMedia = Set.empty[Checksums.MediumChecksum]

  /** The child actor for remove metadata files operation. */
  private var removeActor: ActorRef = _

  /** The current number of active reader actors. */
  private var activeReaderActors = 0

  /** Stores information about a pending close request. */
  private var closeRequest: Option[ActorRef] = None

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit =
    super.preStart()
    removeActor = createChildActor(MetadataFileRemoveActor())

  override def receive: Receive =
    case ScanForMetadataFiles =>
      triggerMetadataFileScan()
      checksumMapping = Map.empty
      modifiedMedia = Set.empty
      mediaMissingMetadata = Set.empty

    case MetadataFileResult(files) =>
      optMetadataFiles = Some(files)
      processPendingScanResults(pendingScanResults)

    case res: EnhancedMediaScanResult if closeRequest.isEmpty =>
      processPendingScanResults(res :: pendingScanResults)
      checksumMapping = checksumMapping ++ res.checksumMapping

    case result: MetadataProcessingSuccess =>
      val optMediaData = mediaInProgress get result.mediumID
      optMediaData foreach { d =>
        d.listenerActor ! result
        mediaInProgress = mediaInProgress.updated(d.mediumID, d updateResolvedFiles result)
      }

    case MetadataExtractionCompleted(mediumID, success) =>
      optMetadataFiles = optMetadataFiles map : metadataFiles =>
        checksumMapping.get(mediumID) match
          case Some(cs) if success => addMetadataFile(metadataFiles, cs)
          case Some(cs) => removeMetadataFile(metadataFiles, cs)
          case None => metadataFiles
      mediaMissingMetadata = mediaMissingMetadata - mediumID
      writeToCFile()

    case FetchMetadataFileInfo(controller) =>
      sender() ! fetchCurrentMetaFileInfo(controller)

    case req: RemovePersistentMetadata =>
      val (target, msg) = generateRemoveRequestResponse(req, optMetadataFiles, sender())
      target ! msg

    case MetadataFileRemoveActor.RemoveMetadataFilesResult(request, deleted) =>
      request.client ! RemovePersistentMetadataResult(RemovePersistentMetadata(request.fileIDs),
        deleted)
      optMetadataFiles = optMetadataFiles map { files =>
        files.filterNot(t => deleted.contains(t._1.checksum))
      }

    case ScanCompleted =>
      writeToCFile()

    case TocWriterResult(None, Some(exception)) =>
      log.error(exception, "Failed to write ToC file.")

    case TocWriterResult(optTocPath, None) =>
      optTocPath.foreach: path =>
        log.info("ToC file has been written to path {}.", path)

    case Terminated(reader) =>
      activeReaderActors -= 1
      closeRequest match
        case Some(rec) =>
          checkAndSendCloseAck(rec)
        case None => startReaderActors()

      val optMediumData = mediaInProgress.values find (_.readerActor == reader)
      optMediumData foreach : d =>
        val unresolvedFiles = d.unresolvedFiles(converter)
        unresolvedFiles foreach (processUnresolvedFiles(_, d.listenerActor))
        mediaInProgress = mediaInProgress - d.mediumID

    case CloseRequest =>
      closeRequest = Some(sender())
      mediaInProgress = Map.empty
      pendingReadRequests = Nil
      checkAndSendCloseAck(sender())

  /**
    * Writes or updates the ToC file for this archive if necessary when all
    * information required is available.
    */
  private def writeToCFile(): Unit =
    if mediaMissingMetadata.isEmpty then
      config.contentFile foreach : target =>
        val info = fetchCurrentMetaFileInfo(sender())
        val mediaData = info.metadataFiles.map(e => e._1 -> Checksums.MediumChecksum(e._2))
        val unused = info.unusedFiles.map(Checksums.MediumChecksum.apply)
        val futToc = tocWriter.writeToc(target, mediaData, unused, modifiedMedia)(using context.system)
        import context.dispatcher
        futToc.onComplete:
          case Success(value) =>
            self ! TocWriterResult(value, None)
          case Failure(exception) =>
            self ! TocWriterResult(None, Some(exception))

  /**
    * Invokes the file scanner to start the scan for metadata files. When
    * the future with the scan result completes, the result is sent as a
    * message to this actor.
    */
  private def triggerMetadataFileScan(): Unit =
    log.info("Scanning {} for metadata files.", config.metadataPersistencePath)
    import context.dispatcher
    implicit val system: ActorSystem = context.system
    fileScanner.scanForMetadataFiles(config.metadataPersistencePath, config.blockingDispatcherName)
      .recover {
        case e: Exception =>
          log.error(e, "Could not read metadata files!")
          Map.empty[MediumChecksum, Path]
      } map (m => MetadataFileResult(m)) foreach (self ! _)

  /**
    * Generates the response to send in reaction on a request to remove 
    * metadata files. If the information about these files is not yet 
    * available, the caller is sent a result immediately (indicating that no 
    * files have been deleted); otherwise, the request is passed to the remove 
    * actor.
    *
    * @param req      the request to remove files
    * @param fileData the current metadata file information
    * @param caller   the calling actor
    * @return a tuple with the target actor and the message to send
    */
  private def generateRemoveRequestResponse(req: RemovePersistentMetadata,
                                            fileData: Option[Map[MediumChecksum, Path]],
                                            caller: ActorRef): (ActorRef, Any) =
    fileData match
      case Some(files) =>
        val nameMapping = files map (e => e._1.checksum -> e._2)
        (removeActor, MetadataFileRemoveActor.RemoveMetadataFiles(req.checksumSet,
          nameMapping, caller))
      case None =>
        (caller, RemovePersistentMetadataResult(req, Set.empty))

  /**
    * Creates a child actor for reading a metadata file.
    *
    * @return the child reader actor
    */
  private def createChildReaderActor(): ActorRef =
    createChildActor(PersistentMetadataReaderActor(self, config
      .metadataPersistenceChunkSize))

  /**
    * Creates a child actor for reading a metadata file and sends it a read
    * request.
    *
    * @param request the request for the child actor
    * @return the child reader actor
    */
  private def createAndStartChildReaderActor(request: PersistentMetadataReaderActor
  .ReadMetadataFile): ActorRef =
    val reader = createChildReaderActor()
    reader ! request
    context watch reader
    reader

  /**
    * Processes the specified pending scan results. The results are grouped
    * using the grouping functions; then the groups are handled accordingly.
    *
    * @param pendingResults the pending results to be processed
    */
  private def processPendingScanResults(pendingResults: List[EnhancedMediaScanResult]): Unit =
    val (pending, unresolved, requests) = groupPendingScanResults(optMetadataFiles,
      pendingResults)
    unresolved foreach (processUnresolvedFiles(_, sender()))
    pendingReadRequests = requests ::: pendingReadRequests
    startReaderActors()
    pendingScanResults = pending

  /**
    * Processes an ''UnresolvedMetaDataFiles'' message for a medium for which
    * no metadata file could be found.
    *
    * @param u                the message to be processed
    * @param metaManagerActor the metadata manager actor
    */
  private def processUnresolvedFiles(u: UnresolvedMetadataFiles, metaManagerActor: ActorRef): Unit =
    val metadataPersistenceSink = persistMetadataSink(u)
    val unresolvedMsgWithSink = u.copy(metadataSink = metadataPersistenceSink)
    metaManagerActor ! unresolvedMsgWithSink
    mediaMissingMetadata = mediaMissingMetadata + u.mediumID

  /**
    * Generates the path for a metadata file based on the specified
    * ''UnresolvedMetaDataFiles'' object.
    *
    * @param u the object describing unresolved files on a medium
    * @return the path for the corresponding metadata file
    */
  private def generateMetadataPath(u: UnresolvedMetadataFiles): Path =
    generateMetadataPath(u.result.checksumMapping(u.mediumID))

  /**
    * Generates the path for a metadata file based on the specified checksum.
    *
    * @param checksum the checksum
    * @return the path for the corresponding metadata file
    */
  private def generateMetadataPath(checksum: MediumChecksum): Path =
    config.metadataPersistencePath.resolve(checksum.checksum + MetadataFileExtension)

  /**
    * Constructs a [[Sink]] that can be used to persist metadata to a file
    * during the metadata extraction process for a medium. The sink accepts
    * metadata processing results, converts the successful ones to JSON and
    * writes them to a file in the metadata folder.
    *
    * @param unresolvedMsg the message about unresolved files
    * @return the [[Sink]] to persist metadata during extraction
    */
  private def persistMetadataSink(unresolvedMsg: UnresolvedMetadataFiles):
  Sink[MetadataProcessingResult, Future[Any]] =
    val mapStage = Flow[MetadataProcessingSuccess].map: suc =>
      MetadataParser.MetadataWithUri(suc.uri.uri, suc.metadata)
    val listStage = ListSeparatorStage.jsonStage[MetadataParser.MetadataWithUri]
    val fileSink = FileIO.toPath(generateMetadataPath(unresolvedMsg))
    val writerFlow = FilterInstanceOfStage[MetadataProcessingSuccess].via(mapStage).via(listStage)
    writerFlow.toMat(fileSink)(Keep.right)

  /**
    * Starts as many reader actors for metadata files as possible. For each
    * medium request not yet in progress an actor is started until the maximum
    * number of parallel read actors is reached.
    */
  private def startReaderActors(): Unit =
    val (requests, inProgress, count) = updateMediaInProgress(pendingReadRequests,
      mediaInProgress, activeReaderActors)
    mediaInProgress = inProgress
    pendingReadRequests = requests
    activeReaderActors = count

  /**
    * Groups a list with pending scan results. If metadata files are
    * already available, the media in all scan results are grouped whether a
    * corresponding metadata file exists for them. The resulting lists can be
    * used for further processing.
    *
    * @param optMetaDataFiles an option with metadata files
    * @param scanResults      a list with pending scan results
    * @return the updated list of pending results and lists for further
    *         processing of affected media
    */
  private def groupPendingScanResults(optMetaDataFiles: Option[Map[MediumChecksum, Path]],
                                      scanResults: List[EnhancedMediaScanResult]):
  (List[EnhancedMediaScanResult], List[UnresolvedMetadataFiles], List[MediumData]) =
    optMetaDataFiles match
      case Some(map) =>
        val resGroups = scanResults.map(groupMedia(_, map)).unzip
        (Nil, resGroups._1.flatten, resGroups._2.flatten)
      case None =>
        (scanResults, Nil, Nil)

  /**
    * Groups all media in the specified scan result whether a metadata file
    * for them exists or not. The resulting tuple of lists can be used to
    * further process the media.
    *
    * @param res       the current scan result
    * @param dataFiles the map with available data files
    * @return a tuple with sequences about unresolved media and read requests
    */
  private def groupMedia(res: EnhancedMediaScanResult, dataFiles: Map[MediumChecksum, Path]):
  (List[UnresolvedMetadataFiles], List[MediumData]) =
    res.scanResult.mediaFiles.foldLeft(
      (List.empty[UnresolvedMetadataFiles], List.empty[MediumData])):
      (t, e) => groupMedium(e._1, dataFiles, res, t._1, t._2)

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
  private def groupMedium(mediumID: MediumID, dataFiles: Map[MediumChecksum, Path],
                          res: EnhancedMediaScanResult,
                          unresolved: List[UnresolvedMetadataFiles],
                          readRequests: List[MediumData]):
  (List[UnresolvedMetadataFiles], List[MediumData]) =
    dataFiles get res.checksumMapping(mediumID) match
      case Some(path) =>
        (unresolved, MediumData(request = PersistentMetadataReaderActor.ReadMetadataFile(path,
          mediumID),
          scanResult = res, listenerActor = sender()) :: readRequests)
      case None =>
        (UnresolvedMetadataFiles(mediumID, res.scanResult.mediaFiles(mediumID), res, Nil, Sink.ignore) :: unresolved,
          readRequests)

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
    requests match
      case h :: t if readerCount < config.metadataPersistenceParallelCount =>
        val reader = createAndStartChildReaderActor(h.request)
        updateMediaInProgress(t, inProgress + (h.request.mediumID -> h.assignReaderActor(reader))
          , readerCount + 1)
      case _ => (requests, inProgress, readerCount)

  /**
    * Returns an object with information about the metadata files managed by
    * this actor.
    *
    * @param controller the controller actor for the file info object
    * @return information about metadata files
    */
  private def fetchCurrentMetaFileInfo(controller: ActorRef): MetadataFileInfo =
    optMetadataFiles map (createMetadataFileInfo(_, checksumMapping, controller)) getOrElse EmptyMetadataFileInfo

  /**
    * Creates an object with information about metadata files.
    *
    * @param metadataFiles the map with metadata files
    * @param checkMap      the checksum mapping
    * @param controller    the controller actor for the file info object
    * @return the ''MetaDataFileInfo'' object
    */
  private def createMetadataFileInfo(metadataFiles: Map[MediumChecksum, Path], checkMap: Map[MediumID, MediumChecksum],
                                     controller: ActorRef): MetadataFileInfo =
    val assignedFiles = checkMap filter (e => metadataFiles contains e._2)
    val usedFiles = assignedFiles.values.toSet
    val unusedFiles = metadataFiles.keySet diff usedFiles

    val assignedFilesStr = assignedFiles map (e => e._1 -> e._2.checksum)
    val unusedFilesStr = unusedFiles map (_.checksum)
    MetadataFileInfo(assignedFilesStr, unusedFilesStr, Some(controller))

  /**
    * Adds a newly written metadata file to the mapping of metadata files.
    *
    * @param metadataFiles the metadata file mapping
    * @param checksum      the checksum of the affected file
    * @return the updated metadata file mapping
    */
  private def addMetadataFile(metadataFiles: Map[MediumChecksum, Path],
                              checksum: MediumChecksum): Map[MediumChecksum, Path] =
    modifiedMedia = modifiedMedia + checksum
    metadataFiles + (checksum -> generateMetadataPath(checksum))

  /**
    * Removes a file from the map with metadata files after a failed write
    * operation.
    *
    * @param metadataFiles the metadata file mapping
    * @param checksum      the checksum of the affected file
    * @return the updated metadata file mapping
    */
  private def removeMetadataFile(metadataFiles: Map[MediumChecksum, Path],
                                 checksum: MediumChecksum): Map[MediumChecksum, Path] =
    metadataFiles - checksum

  /**
    * Checks whether a close request can be answered. If so, the Ack message
    * is sent to the given receiver, and the internal state is updated.
    *
    * @param receiver the receiver
    */
  private def checkAndSendCloseAck(receiver: ActorRef): Unit =
    if activeReaderActors == 0 then
      receiver ! CloseAck(self)
      closeRequest = None
