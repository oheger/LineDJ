package de.oliver_heger.splaya.media

import java.io.IOException
import java.nio.file.{Path, Paths}

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import de.oliver_heger.splaya.io.FileLoaderActor.{FileContent, LoadFile}
import de.oliver_heger.splaya.io.{ChannelHandler, FileLoaderActor, FileReaderActor}
import de.oliver_heger.splaya.playback.{AudioSourceDownloadResponse, AudioSourceID}
import de.oliver_heger.splaya.utils.ChildActorFactory

/**
 * Companion object.
 */
object MediaManagerActor {

  /**
   * Constant for the medium ID assigned to all other files which do not belong
   * to any other medium.
   */
  val MediumIDOtherFiles = ""

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
  case class ScanMedia(roots: Seq[String])

  /**
   * A message processed by ''MediaManagerActor'' telling it to respond with a
   * list of media currently available. This message is sent by clients in
   * order to find out about the audio data available. They can then decide
   * which audio sources are requested for playback.
   */
  case object GetAvailableMedia

  /**
   * A message processed by ''MediaManagerActor'' telling it to return a list
   * with the files contained on the specified medium.
   *
   * @param mediumID the ID of the medium in question
   */
  case class GetMediumFiles(mediumID: String)

  /**
   * A message sent by ''MediaManagerActor'' which contains information about
   * all media currently available. The media currently available are passed as
   * a map with alphanumeric media IDs as keys and the corresponding info
   * objects as values.
   *
   * @param media a map with information about all media currently available
   */
  case class AvailableMedia(media: Map[String, MediumInfo])

  /**
   * A message sent by ''MediaManagerActor'' in response to a request for the
   * files on a medium. This message contains a sequence with the URIs of the
   * files stored on this medium. The ''existing'' flag can be evaluated if the
   * list is empty: a value of '''true''' means that the medium exists, but
   * does not contain any files; a value of '''false''' indicates an unknown
   * medium.
   * @param mediumID the ID of the medium that was queried
   * @param uris a sequence with the URIs for the files on this medium
   * @param existing a flag whether the medium exists
   */
  case class MediumFiles(mediumID: String, uris: Set[String], existing: Boolean)

  /**
   * Constant for a prototype of a ''MediumFiles'' message for an unknown
   * medium.
   */
  private val UnknownMediumFiles = MediumFiles(null, Set.empty, existing = false)

  /**
   * Constant for a ''MediaFile'' referring to a non-existing file.
   */
  private val NonExistingFile = MediaFile(path = null, size = -1)

  private class MediaManagerActorImpl extends MediaManagerActor with ChildActorFactory

  /**
   * Creates a ''Props'' object for creating new actor instances of this class.
   * Client code should always use the ''Props'' object returned by this
   * method; it ensures that all dependencies have been resolved.
   * @return a ''Props'' object for creating actor instances
   */
  def apply(): Props = Props[MediaManagerActorImpl]

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
   * Creates a dummy ''MediumSettingsData'' object for a medium description
   * file which could not be loaded.
   * @param path the path to the description file
   * @return the dummy settings data
   */
  private def createDummySettingsDataForPath(path: Path): MediumSettingsData =
    MediumInfoParserActor.DummyMediumSettingsData.copy(mediumURI = pathToURI
      (mediumPathFromDescription(path)))
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
 */
class MediaManagerActor extends Actor with ActorLogging {
  this: ChildActorFactory =>

  import MediaManagerActor._

  /** A helper object for scanning directory structures. */
  private[media] val directoryScanner = new DirectoryScanner(Set.empty)

  /** A helper object for calculating media IDs. */
  private[media] val idCalculator = new MediumIDCalculator

  /** A helper object for parsing medium description files. */
  private[media] val mediumInfoParser = new MediumInfoParser

  /** The actor for loading files. */
  private var loaderActor: ActorRef = _

  /** The map with the media currently available. */
  private var mediaMap = Map.empty[String, MediumInfo]

  /**
   * A temporary map for storing media ID information. It is used while
   * constructing the information about the currently available media.
   */
  private val mediaIDData = collection.mutable.Map.empty[String, MediumIDData]

  /**
   * A temporary map for storing media settings data extracted from media
   * description files. It is used while constructing the information about the
   * currently available media.
   */
  private val mediaSettingsData = collection.mutable.Map.empty[String, MediumSettingsData]

  /**
   * A map with information about the files contained in the currently
   * available media.
   */
  private val mediaFiles = collection.mutable.Map.empty[String, Map[String, MediaFile]]

  /**
   * Stores references to clients that have asked for the available media
   * before this information has been fetched. As soon as the data about the
   * media available is complete, these actors will receive a notification.
   */
  private var pendingMediaRequest = List.empty[ActorRef]

  /** The number of paths which have to be scanned in a current scan operation. */
  private var pathsToScan = 0

  /** The number of paths that have already been scanned. */
  private var pathsScanned = -1

  /** The number of available media.*/
  private var mediaCount = 0

  /**
   * The supervisor strategy used by this actor stops the affected child on
   * receiving an IO exception. This is used to detect failed scan operations.
   */
  override val supervisorStrategy = OneForOneStrategy() {
    case _: IOException => Stop
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    loaderActor = createChildActor(Props[FileLoaderActor])
  }

  override def receive: Receive = {
    case ScanMedia(roots) =>
      processScanRequest(roots)

    case scanResult: MediaScanResult =>
      processScanResult(scanResult)
      context unwatch sender()
      stopSender()

    case FileContent(path, content) =>
      processMediumDescription(path, content)

    case idData: MediumIDData =>
      if (idData.mediumURI == MediumIDOtherFiles) {
        appendMedium(idData.mediumID, MediumInfoParserActor.undefinedMediumInfo)
      } else {
        mediaIDData += idData.mediumURI -> idData
        createAndStoreMediumInfo(idData.mediumURI)
      }
      mediaFiles += idData.mediumID -> idData.fileURIMapping
      stopSender()

    case setData: MediumSettingsData =>
      storeSettingsData(setData)
      stopSender()

    case GetAvailableMedia =>
      if(mediaInformationComplete) {
        sender ! AvailableMedia(mediaMap)
      } else {
        pendingMediaRequest = sender() :: pendingMediaRequest
      }

    case GetMediumFiles(mediumID) =>
      val optResponse = mediaFiles.get(mediumID) map
        (files => MediumFiles(mediumID, files.keySet, existing = true))
      sender ! optResponse.getOrElse(UnknownMediumFiles.copy(mediumID = mediumID))

    case sourceID: AudioSourceID =>
      val readerActor = createChildActor(Props[FileReaderActor])
      val optFile = fetchMediaFile(sourceID)
      optFile foreach (f => readerActor ! ChannelHandler.InitFile(f.path))
      sender ! AudioSourceDownloadResponse(sourceID, readerActor, optFile.getOrElse
        (NonExistingFile).size)

    case _: Terminated =>
      handleActorTermination()

    case ChannelHandler.IOOperationError(path, ex) =>
      log.warning("Loading a description file caused an exception: {}!", ex)
      storeSettingsData(createDummySettingsDataForPath(path))
  }

  /**
   * Processes a request for scanning directory structures. If this request is
   * allowed in the current state of this actor, the scanning of the desired
   * directory structures is initiated.
   * @param roots a sequence with the root directories to be scanned
   */
  private def processScanRequest(roots: Seq[String]): Unit = {
    if (noScanInProgress) {
      mediaMap = Map.empty
      mediaCount = 0
      pathsToScan = roots.size
      pathsScanned = 0
      scanMediaRoots(roots)
    } else log.warning("Ignoring scan request for {}. Scan already in progress.", roots)
  }

  /**
   * Initiates scanning of the root directories with media files.
   * @param roots a sequence with the root directories to be scanned
   */
  private def scanMediaRoots(roots: Seq[String]): Unit = {
    log.info("Processing scan request for roots {}.", roots)
    roots foreach { root =>
      val dirScannerActor = createChildActor(Props(classOf[DirectoryScannerActor],
        directoryScanner))
      context watch dirScannerActor
      dirScannerActor ! DirectoryScannerActor.ScanPath(Paths.get(root))
    }
    mediaDataAdded()
  }

  /**
   * Processes the result of a scan operation of a root directory. This method
   * triggers the calculation of media IDs and parsing of medium description
   * files.
   * @param scanResult the data object with scan results
   */
  private def processScanResult(scanResult: MediaScanResult): Unit = {
    def triggerIDCalculation(mediumPath: Path, mediumURI: String, files: Seq[MediaFile]): Unit = {
      val idActor = createChildActor(Props(classOf[MediumIDCalculatorActor], idCalculator))
      idActor ! MediumIDCalculatorActor.CalculateMediumID(mediumPath, mediumURI, files)
    }

    scanResult.mediaFiles foreach { e =>
      loaderActor ! LoadFile(e._1)
      val mediumPath = mediumPathFromDescription(e._1)
      triggerIDCalculation(mediumPath, pathToURI(mediumPath), e._2)
    }
    mediaCount += scanResult.mediaFiles.size

    if (scanResult.otherFiles.nonEmpty) {
      mediaCount += 1
      triggerIDCalculation(scanResult.root, MediumIDOtherFiles, scanResult.otherFiles)
      processOtherFiles(scanResult)
    }

    incrementScannedPaths()
  }

  /**
   * Processes other files in a scan result. Information about a combined list
   * of files which do not belong to a medium has to be stored by the actor.
   * This is handled by this method.
   * @param scanResult the data object with scan results
   */
  private def processOtherFiles(scanResult: MediaScanResult): Unit = {
    if (!mediaMap.contains(MediumIDOtherFiles)) {
      mediaCount += 1
      appendMedium(MediumIDOtherFiles, MediumInfoParserActor.undefinedMediumInfo)
    }
    val otherMapping = createOtherFilesMapping(scanResult)
    val currentOtherMapping = mediaFiles.getOrElse(MediumIDOtherFiles, Map.empty)
    mediaFiles += MediumIDOtherFiles -> (currentOtherMapping ++ otherMapping)
  }

  /**
   * Creates a URI mapping for other files from a ''MediaScanResult''. This
   * mapping will become part of a global mapping for all files that do not
   * belong to a specific medium.
   * @param scanResult the ''MediaScanResult''
   * @return the resulting mapping
   */
  private def createOtherFilesMapping(scanResult: MediaScanResult): Map[String, MediaFile] = {
    val otherURIs = scanResult.otherFiles map { f => pathToURI(f.path) }
    Map(otherURIs zip scanResult.otherFiles: _*)
  }

  /**
   * Processes the data of a medium description (in binary form). This method
   * is called when a description file has been loaded. Now it has to be
   * parsed.
   * @param path the path to the description file
   * @param content the binary content of the description file
   */
  private def processMediumDescription(path: Path, content: Array[Byte]): Unit = {
    val parserActor = createChildActor(Props(classOf[MediumInfoParserActor], mediumInfoParser))
    parserActor ! MediumInfoParserActor.ParseMediumInfo(content, pathToURI
      (mediumPathFromDescription(path)))
  }

  /**
   * Stores a ''MediumSettingsData'' object which has been created by a child
   * actor.
   * @param data the data object to be stored
   */
  private def storeSettingsData(data: MediumSettingsData): Unit = {
    mediaSettingsData += data.mediumURI -> data
    createAndStoreMediumInfo(data.mediumURI)
  }

  /**
   * Checks whether all information for creating a ''MediumInfo'' object is
   * available for the specified medium URI. If so, the object is created and
   * stored in the global map.
   * @param mediumURI the affected medium URI
   */
  private def createAndStoreMediumInfo(mediumURI: String): Unit = {
    for {idData <- mediaIDData.get(mediumURI)
         settingsData <- mediaSettingsData.get(mediumURI)
    } {
      appendMedium(idData.mediumID, settingsData)
    }
  }

  /**
   * Obtains the ''MediaFile'' object referred to by the given
   * ''AudioSourceID''. The file is looked up in the data structures managed by
   * this actor. If it cannot be found, result is ''None''.
   * @param sourceID the ID identifying the desired file
   * @return an option with the ''MediaFile''
   */
  private def fetchMediaFile(sourceID: AudioSourceID): Option[MediaFile] = {
    mediaFiles get sourceID.mediumID flatMap (_.get(sourceID.uri))
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
   * @return a flag whether all information is now complete
   */
  private def mediaInformationComplete: Boolean =
    pathsScanned >= pathsToScan && mediaMap.size >= mediaCount

  /**
   * Sends information about the currently available media to pending actors.
   * This method is called when all data about media has been fetched. Actors
   * which have requested this information before have to be notified now.
   */
  private def handlePendingMediaRequests(): Unit = {
    if (pendingMediaRequest.nonEmpty) {
      val msg = AvailableMedia(mediaMap)
      pendingMediaRequest foreach (_ ! msg)
    }
  }

  /**
   * Notifies this object that new media information has been added. If this
   * information is now complete, the scan operation can be terminated, and
   * pending requests can be handled.
   * @return a flag whether the data about media is now complete
   */
  private def mediaDataAdded(): Boolean = {
    if (mediaInformationComplete) {
      handlePendingMediaRequests()
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
    pendingMediaRequest = List.empty
    mediaIDData.clear()
    mediaSettingsData.clear()
  }

  /**
   * Appends another entry to the map with media data. If the data is now
   * complete, the scan operation is terminated.
   * @param mediumID the ID of the medium
   * @param info the medium info object
   * @return a flag whether the data about media is now complete
   */
  private def appendMedium(mediumID: String, info: MediumInfo): Boolean = {
    mediaMap += mediumID -> info
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
   * @return a flag whether currently no scan request is in progress
   */
  private def noScanInProgress: Boolean = pathsScanned < 0

  /**
   * Handles an actor terminated message. A message of this type indicates that
   * a directory scanner actor threw an exception. In this case, the
   * corresponding directory structure is excluded/ignored.
   */
  private def handleActorTermination(): Unit = {
    log.warning("Received Terminated message.")
    incrementScannedPaths()
  }
}
