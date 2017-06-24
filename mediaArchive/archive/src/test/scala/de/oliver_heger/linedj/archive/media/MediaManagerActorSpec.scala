package de.oliver_heger.linedj.archive.media

import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import akka.actor._
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archivecommon.download.{DownloadConfig, DownloadManagerActor}
import de.oliver_heger.linedj.archivecommon.parser.MediumInfoParser
import de.oliver_heger.linedj.extract.id3.model.ID3HeaderExtractor
import de.oliver_heger.linedj.io._
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.shared.archive.union.{AddMedia, ArchiveComponentRemoved, RemovedArchiveComponentProcessed}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.commons.configuration.PropertiesConfiguration
import org.mockito.Matchers.{eq => argEq}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.reflect.ClassTag

object MediaManagerActorSpec {
  /** Class for the directory scanner child actor. */
  val ClsDirScanner: Class[MediaScannerActor] = classOf[MediaScannerActor]

  /** Class for the ID calculator child actor. */
  val ClsIDCalculator: Class[MediumIDCalculatorActor] = classOf[MediumIDCalculatorActor]

  /** Class for the medium info parser child actor. */
  val ClsInfoParser: Class[MediumInfoParserActor] = classOf[MediumInfoParserActor]

  /** Class for the media reader actor child actor. */
  val ClsMediaReaderActor: Class[MediaFileReaderActor] = classOf[MediaFileReaderActor]

  /** Class for the file loader actor. */
  val ClsFileLoaderActor: Class[_ <: Actor] = FileLoaderActor().actorClass()

  /** Class for the download manager actor. */
  val ClsDownloadManagerActor: Class[_ <: Actor] =
    DownloadManagerActor(DownloadConfig(new PropertiesConfiguration)).actorClass()

  /** A special test message sent to actors. */
  private val TestMessage = new Object

  /** The interval for reader actor timeout checks. */
  private val ReaderCheckInterval = 5.minutes

  /** The set with excluded file extensions. */
  private val ExcludedExtensions = Set("TXT", "JPG")

  /** The maximum size of medium description files. */
  private val InfoSizeLimit = 9876

  /** The chunk size for download operations. */
  private val DownloadChunkSize = 11111

  /**
    * Conversion function from a string to a path.
    *
    * @param s the string
    * @return the path
    */
  private def asPath(s: String): Path = Paths get s

  /**
   * Helper method to ensure that no more messages are sent to a test probe.
   * This message sends a special message to the probe and checks whether it is
   * immediately received.
   * @param probe the probe to be checked
   */
  private def expectNoMoreMessage(probe: TestProbe): Unit = {
    probe.ref ! TestMessage
    probe.expectMsg(TestMessage)
  }

  /**
    * Constructs a message mapping for the medium info parser actor. A parse
    * medium info request is assigned a response message.
    *
    * @param descPath the description path to be parsed
    * @param mid      the medium ID
    * @param info     the resulting medium info
    * @return the mapping
    */
  private def mediumInfoRequestMapping(descPath: Path, mid: MediumID, info: MediumInfo):
  (Any, Any) = {
    val request = MediumInfoParserActor.ParseMediumInfo(descPath, mid, 0)
    val response = MediumInfoParserActor.ParseMediumInfoResult(request, info)
    request -> response
  }

  /**
    * Constructs a message mapping for the medium scanner actor. A ScanPath
    * request is assigned a response message.
    *
    * @param path       the path to be scanned
    * @param scanResult the result of the scan operation
    * @return the mapping
    */
  private def scanRequestMapping(path: Path, scanResult: MediaScanResult): (Any, Any) = {
    val request = MediaScannerActor.ScanPath(path, 0)
    val response = MediaScannerActor.ScanPathResult(request, scanResult)
    request -> response
  }

  /**
    * Constructs a message mapping for the ID calculator actor. An ID
    * calculation request is assigned a response message.
    *
    * @param path   the root path
    * @param mid    the medium ID
    * @param sr     the scan result
    * @param files  the sequence with files
    * @param result the result
    * @return the mapping
    */
  private def idRequestMapping(path: Path, mid: MediumID, sr: MediaScanResult,
                               files: Seq[FileData], result: MediumIDData): (Any, Any) = {
    val request = MediumIDCalculatorActor.CalculateMediumID(path, mid, sr, files, 0)
    val response = MediumIDCalculatorActor.CalculateMediumIDResult(request, result)
    request -> response
  }

  /**
    * Determines the sequence number from the specified message.
    *
    * @param msg the message
    * @return the sequence number
    */
  private def extractSeqNo(msg: Any): Int = msg match {
    case m: MediaScannerActor.ScanPath => m.seqNo
    case m: MediumInfoParserActor.ParseMediumInfo => m.seqNo
    case m: MediumIDCalculatorActor.CalculateMediumID => m.seqNo
  }

  /**
    * Updates the sequence number for the specified message. The messages are
    * just templates; they have to be assigned the correct sequence number.
    *
    * @param msg   the message
    * @param seqNo the sequence number
    * @return the updated message
    */
  private def updateSeqNo(msg: Any, seqNo: Int): Any = msg match {
    case m: MediaScannerActor.ScanPath =>
      m.copy(seqNo = seqNo)

    case m: MediaScannerActor.ScanPathResult =>
      MediaScannerActor.ScanPathResult(m.request.copy(seqNo = seqNo), m.result)

    case m: MediumInfoParserActor.ParseMediumInfo =>
      m.copy(seqNo = seqNo)

    case m: MediumInfoParserActor.ParseMediumInfoResult =>
      MediumInfoParserActor.ParseMediumInfoResult(m.request.copy(seqNo = seqNo), m.info)

    case m: MediumIDCalculatorActor.CalculateMediumID =>
      m.copy(seqNo = seqNo)

    case m: MediumIDCalculatorActor.CalculateMediumIDResult =>
      MediumIDCalculatorActor.CalculateMediumIDResult(m.request.copy(seqNo = seqNo), m.result)
  }

  /**
    * Internal data class to store information about test probes created for
    * child actors.
    *
    * @param probe the probe
    * @param props the creation Props of this child actor
    */
  private case class ProbeData(probe: TestProbe, props: Props)

}

/**
 * Test class for ''MediaManagerActor''.
 */
class MediaManagerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {
  import MediaManagerActorSpec._

  def this() = this(ActorSystem("MediaManagerActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
   * Creates a mock configuration object.
   * @return the mock configuration
   */
  private def createConfiguration(): MediaArchiveConfig = {
    val downloadConfig = mock[DownloadConfig]
    val config = mock[MediaArchiveConfig]
    when(downloadConfig.downloadTimeout).thenReturn(60.seconds)
    when(downloadConfig.downloadCheckInterval).thenReturn(ReaderCheckInterval)
    when(downloadConfig.downloadChunkSize).thenReturn(DownloadChunkSize)
    when(config.excludedFileExtensions).thenReturn(ExcludedExtensions)
    when(config.infoSizeLimit).thenReturn(InfoSizeLimit)
    when(config.downloadConfig).thenReturn(downloadConfig)
    config
  }

  "A MediaManagerActor" should "create a correct Props object" in {
    val config = createConfiguration()
    val unionActor = TestProbe()
    val props = MediaManagerActor(config, testActor, unionActor.ref)
    props.args should be(List(config, testActor, unionActor.ref))

    val manager = TestActorRef[MediaManagerActor](props)
    manager.underlyingActor shouldBe a[MediaManagerActor]
    manager.underlyingActor shouldBe a[ChildActorFactory]
    manager.underlyingActor shouldBe a[CloseSupport]
  }

  it should "create default helper objects" in {
    val manager = TestActorRef[MediaManagerActor](MediaManagerActor(createConfiguration(),
      testActor, TestProbe().ref))
    manager.underlyingActor.idCalculator shouldBe a[MediumIDCalculator]
    manager.underlyingActor.mediumInfoParser shouldBe a[MediumInfoParser]
  }

  it should "pass media data to the media union actor" in {
    val helper = new MediaManagerTestHelper
    helper.scanMedia()

    helper checkMediaWithDescriptions helper.expectMediaAdded()
  }

  it should "stop temporary child actors when their answers are received" in {
    val helper = new MediaManagerTestHelper
    helper.scanMedia()

    val exitProbe = TestProbe()
    for {cls <- List[Class[_]](classOf[MediumIDCalculatorActor])
         probe <- helper.probesOfActorClass(cls)} {
      exitProbe watch probe.ref
      exitProbe.expectMsgType[Terminated]
    }
  }

  it should "include medium IDs for other files" in {
    val helper = new MediaManagerTestHelper
    helper.scanMedia()

    val media = helper.expectMediaAdded()
    media(MediumID(helper.Drive1Root.toString, None)) should be(MediumInfoParserActor
      .undefinedMediumInfo.copy(checksum = helper.Drive1OtherIDData.checksum))
    media(MediumID(helper.Drive3Root.toString, None)) should be(MediumInfoParserActor
      .undefinedMediumInfo.copy(checksum = helper.Drive3OtherIDData.checksum))
  }

  /**
   * Prepares a test helper instance for a test which requires scanned media.
   * @return the test helper
   */
  private def prepareHelperForScannedMedia(): MediaManagerTestHelper = {
    val helper = new MediaManagerTestHelper
    helper.scanMedia()
    helper
  }

  it should "support queries for the files on a medium" in {
    val helper = prepareHelperForScannedMedia()

    helper.testManagerActor ! GetMediumFiles(helper.definedMediumID(1, helper.Medium1Path))
    val msgFiles = expectMsgType[MediumFiles]
    msgFiles.mediumID should be(helper.Medium1IDData.mediumID)
    msgFiles.existing shouldBe true
    helper.Medium1IDData.fileURIMapping.keySet should contain theSameElementsAs msgFiles.uris
  }

  it should "answer a query for the files of a non-existing medium" in {
    val helper = prepareHelperForScannedMedia()

    val request = GetMediumFiles(MediumID("non existing path", None))
    helper.testManagerActor ! request
    val msgFiles = expectMsgType[MediumFiles]
    msgFiles.mediumID should be(request.mediumID)
    msgFiles.uris shouldBe 'empty
    msgFiles.existing shouldBe false
  }

  it should "answer a query for other files on a specific root path" in {
    val helper = prepareHelperForScannedMedia()
    val expURIs = helper.Drive3OtherFiles map ("path://" + _.path.toString)

    helper.testManagerActor ! GetMediumFiles(MediumID(helper.Drive3Root.toString, None))
    val msgFiles = expectMsgType[MediumFiles]
    msgFiles.uris.size should be(expURIs.size)
    msgFiles.uris.subsetOf(expURIs.toSet) shouldBe true
  }

  it should "create a valid ID3 data extractor" in {
    val helper = new MediaManagerTestHelper

    helper.testManagerActor.underlyingActor.id3Extractor shouldBe a[ID3HeaderExtractor]
  }

  /**
   * Checks whether a request for a non-existing media file is handled
   * correctly.
   * @param request the ID of the source to be requested
   */
  private def checkUnknownFileRequest(request: MediumFileRequest): Unit = {
    val helper = prepareHelperForScannedMedia()

    helper.testManagerActor ! request
    val response = expectMsgType[MediumFileResponse]
    response.request should be(request)
    response.length should be(-1)
    response.contentReader shouldBe 'empty
    val optDownloadActor = helper.probesOfType[MediaFileDownloadActor].headOption
    optDownloadActor shouldBe 'empty
  }

  it should "return a file response for an unknown medium ID" in {
    checkUnknownFileRequest(MediumFileRequest(MediumID("unknown medium", None), "unknown URI",
      withMetaData = false))
  }

  it should "return a file response for a request with an unknown URI" in {
    val helper = new MediaManagerTestHelper
    checkUnknownFileRequest(MediumFileRequest(helper.Medium1IDData.mediumID, "unknown URI",
      withMetaData = false))
  }

  /**
   * Creates the ID of an audio source which exists in the directory structures
   * scanned by the test actor.
   * @param helper the test helper
   * @param withMetaData a flag whether meta data is to be retrieved
   * @return the request object
   */
  private def createRequestForExistingFile(helper: MediaManagerTestHelper,
                                            withMetaData: Boolean = false): MediumFileRequest = {
    val fileURI = helper.Medium1IDData.fileURIMapping.keys.head
    MediumFileRequest(helper.Medium1IDData.mediumID, fileURI, withMetaData)
  }

  it should "return a correct download result" in {
    val helper = prepareHelperForScannedMedia()

    val request = createRequestForExistingFile(helper)
    val file = helper.Medium1IDData.fileURIMapping(request.uri)
    helper.testManagerActor ! request
    val response = expectMsgType[MediumFileResponse]
    response.request should be(request)
    response.length should be(file.size)

    val downloadProbe = fetchDownloadActor(helper)
    downloadProbe.props.args should be(List(asPath(file.path), DownloadChunkSize, true))
    response.contentReader should be(Some(downloadProbe.probe.ref))
  }

  it should "handle a scan operation that does not yield media" in {
    val helper = new MediaManagerTestHelper

    helper.configureRootPathsForScans(Set.empty).sendScanRequest()
    helper.expectMediaAdded() should have size 0
  }

  it should "reset data for a new scan operation" in {
    val helper = new MediaManagerTestHelper
    helper.configureRootPathsForScans(helper.RootPaths.toSet, Set.empty).scanMedia()
    helper checkMediaWithDescriptions helper.expectMediaAdded()

    helper.sendScanRequest()
    helper.expectRemoveComponentMessage().answerRemoveComponentMessage()
    helper.expectMediaAdded() should have size 0
    helper.testManagerActor ! GetMediumFiles(MediumID("someMedium", None))
    expectMsgType[MediumFiles].uris shouldBe 'empty
  }

  it should "ignore another scan request while a scan is in progress" in {
    val helper = new MediaManagerTestHelper
    helper.configureRootPathsForScans(helper.RootPaths.toSet,
      Set("UnsupportedTestPath")).sendScanRequest()

    helper.scanMedia()
    helper checkMediaWithDescriptions helper.expectMediaAdded()
  }

  it should "handle IO exceptions when scanning directories" in {
    val helper = new MediaManagerTestHelper(childActorFunc = { (ctx, props) =>
      props.actorClass() match {
        case MediaManagerActorSpec.ClsDirScanner =>
          Some(ctx.actorOf(props, "DirScannerActor"))
        case _ => None
      }
    })

    helper.configureRootPathsForScans(Set("non existing directory!")).sendScanRequest()
    helper.expectMediaAdded() should have size 0
  }

  /**
   * Obtains information about the last created download actor.
   * @param helper the test helper
   * @return a data object for the last download actor
   */
  private def fetchDownloadActor(helper: MediaManagerTestHelper): ProbeData =
    helper.probeDataOfType[MediaFileDownloadActor].head

  it should "inform the download manager about newly created download actors" in {
    val helper = prepareHelperForScannedMedia()
    val request = createRequestForExistingFile(helper)
    helper.testManagerActor ! request

    expectMsgType[MediumFileResponse].length should be > 0L
    val probeData = fetchDownloadActor(helper)
    helper.downloadManager.expectMsg(DownloadManagerActor.DownloadOperationStarted(
      probeData.probe.ref, testActor))
  }

  it should "respect the withMetaData flag in a media file request" in {
    val helper = prepareHelperForScannedMedia()
    helper.testManagerActor ! createRequestForExistingFile(helper, withMetaData = true)

    val response = expectMsgType[MediumFileResponse]
    val probeData = fetchDownloadActor(helper)
    response.contentReader should be(Some(probeData.probe.ref))
    probeData.props.args(2) shouldBe false
  }

  it should "handle a file request for a file in global undefined medium" in {
    val helper = prepareHelperForScannedMedia()
    val targetFileUri = helper.Medium1IDData.fileURIMapping.keys.head
    val targetFile = helper.Medium1IDData.fileURIMapping(targetFileUri)
    val fileURI = "ref://" + helper.Medium1IDData.mediumID.mediumURI + ":" +
      helper.Medium1IDData.mediumID.archiveComponentID + ":" +  targetFileUri
    helper.testManagerActor ! MediumFileRequest(MediumID.UndefinedMediumID, fileURI,
      withMetaData = false)

    val response = expectMsgType[MediumFileResponse]
    response.length should be (targetFile.size)
    val downloadProbe = fetchDownloadActor(helper)
    downloadProbe.props.args.head should be(asPath(targetFile.path))
  }

  it should "pass media scan results to the meta data manager" in {
    val helper = new MediaManagerTestHelper
    val checkMap1 = Map(helper.Medium1IDData.mediumID -> helper.Medium1IDData.checksum,
      helper.Medium2IDData.mediumID -> helper.Medium2IDData.checksum,
      helper.Drive1OtherIDData.mediumID -> helper.Drive1OtherIDData.checksum)
    val fileMapping1 = helper.Medium1IDData.fileURIMapping ++ helper.Medium2IDData.fileURIMapping ++ helper.Drive1OtherIDData.fileURIMapping
    val checkMap2 = Map(helper.Medium3IDData.mediumID -> helper.Medium3IDData.checksum)
    val fileMapping2 = helper.Medium3IDData.fileURIMapping
    val checkMap3 = Map(helper.Drive3OtherIDData.mediumID -> helper.Drive3OtherIDData.checksum)
    val fileMapping3 = helper.Drive3OtherIDData.fileURIMapping
    helper.scanMedia()

    helper.metaDataManagerActor.expectMsg(MediaScanStarts)
    val messages = (1 to 3) map (_ => helper.metaDataManagerActor
      .expectMsgType[EnhancedMediaScanResult])
    messages should contain only(EnhancedMediaScanResult(helper.Drive1, checkMap1, fileMapping1),
      EnhancedMediaScanResult(helper.Drive2, checkMap2, fileMapping2),
      EnhancedMediaScanResult(helper.Drive3, checkMap3, fileMapping3))
  }

  it should "notify the meta data manager about a completed scan" in {
    val helper = new MediaManagerTestHelper
    helper.scanMedia()

    helper.metaDataManagerActor.fishForMessage() {
      case MediaScanStarts => false
      case _: EnhancedMediaScanResult => false
      case am: AvailableMedia =>
        helper checkMediaWithDescriptions am.media
        true
    }
    expectNoMoreMessage(helper.metaDataManagerActor)
  }

  it should "produce correct enhanced scan results in another scan operation" in {
    val helper = new MediaManagerTestHelper
    helper.scanMedia()
    helper.checkMetaDataMessages().reset()

    helper.scanMedia()
    helper.answerRemoveComponentMessage()
    helper.checkMetaDataMessages()
  }

  it should "handle a close request" in {
    val helper = new MediaManagerTestHelper

    helper.testManagerActor ! CloseRequest
    helper.numberOfCloseRequests should be(1)
    helper.mediaScannerProbe.expectMsg(AbstractStreamProcessingActor.CancelStreams)
  }

  it should "handle a close complete message" in {
    val helper = new MediaManagerTestHelper

    helper.testManagerActor ! CloseHandlerActor.CloseComplete
    helper.numberOfCompletedCloseOps should be(1)
  }

  it should "send a component removed message before starting another scan" in {
    val helper = new MediaManagerTestHelper
    helper.scanMedia()
    helper.checkMetaDataMessages().expectMediaAdded()

    helper.sendScanRequest()
    helper.expectRemoveComponentMessage()
    expectNoMoreMessage(helper.metaDataManagerActor)
  }

  it should "not send enhanced scan results before a remove component confirmation" in {
    val helper = new MediaManagerTestHelper
    helper.scanMedia()
    helper.checkMetaDataMessages().expectMediaAdded()

    helper.reset().scanMedia()
    helper.expectRemoveComponentMessage()
    expectNoMoreMessage(helper.metaDataManagerActor)
    expectNoMoreMessage(helper.mediaUnionActor)
    helper.answerRemoveComponentMessage()
      .checkMetaDataMessages()
    helper checkMediaWithDescriptions helper.expectMediaAdded()
  }

  it should "clear the list of pending messages after receiving a confirmation" in {
    val helper = new MediaManagerTestHelper
    helper.scanMedia()
    helper.checkMetaDataMessages().expectMediaAdded()
    helper.reset().scanMedia()
    helper.answerRemoveComponentMessage().checkMetaDataMessages()

    helper.answerRemoveComponentMessage()
    expectNoMoreMessage(helper.metaDataManagerActor)
  }

  it should "clear the list of pending messages when receiving a close request" in {
    val helper = new MediaManagerTestHelper
    helper.scanMedia()
    helper.checkMetaDataMessages().expectMediaAdded()
    helper.reset().scanMedia()

    helper.testManagerActor ! CloseRequest
    helper.answerRemoveComponentMessage()
    expectNoMoreMessage(helper.metaDataManagerActor)
  }

  it should "ignore a remove confirmation for another archive component" in {
    val helper = new MediaManagerTestHelper
    helper.scanMedia()
    helper.checkMetaDataMessages().expectMediaAdded()
    helper.reset().scanMedia()

    helper.answerRemoveComponentMessage("some other archive component")
    expectNoMoreMessage(helper.metaDataManagerActor)
  }

  it should "increase the sequence number after a scan operation" in {
    val helper = new MediaManagerTestHelper
    helper.scanMedia()
    val seqNo1 = helper.seqNo

    helper.reset().scanMedia()
    helper.seqNo should not be seqNo1
  }

  it should "ignore scan results with invalid sequence numbers" in {
    val helper = new MediaManagerTestHelper
    helper.sendScanRequest()
    helper.simulateMediaScannerActor()
    val scanRequest = MediaScannerActor.ScanPath(asPath("testPath"), -1)
    val scanResult = MediaScanResult(scanRequest.path,
      Map(MediumID("anotherMedium", Some("more.settings")) -> List(FileData("file1", 222))))

    helper.testManagerActor ! MediaScannerActor.ScanPathResult(scanRequest, scanResult)
    helper.simulateIDCalculatorActor()
    helper.simulateInfoParserActor()
    helper checkMediaWithDescriptions helper.expectMediaAdded()
  }

  it should "ignore ID calculation and info results with invalid sequence numbers" in {
    val helper = new MediaManagerTestHelper
    helper.sendScanRequest()
    helper.simulateMediaScannerActor()
    val scanResult = MediaScanResult(asPath("aPath"),
      Map(MediumID("anotherMedium", Some("more.settings")) -> List(FileData("file1", 222))))
    val mid = MediumID("invalidMedium", Some("invalid.settings"))
    val idRequest = MediumIDCalculatorActor.CalculateMediumID(asPath("invalidPath"),
      mid, scanResult, List(FileData("f1.mp3", 28), FileData("f2.mp3", 32)), -1)
    val idResult = MediumIDData("aCheckSum", idRequest.mediumID, idRequest.scanResult,
      Map.empty)
    val infoRequest = MediumInfoParserActor.ParseMediumInfo(asPath(mid.mediumDescriptionPath.get),
      mid, -1)
    val infoResult = MediumInfo("aMedium", "aDesc", mid, "", "", "")

    helper.testManagerActor ! MediumIDCalculatorActor.CalculateMediumIDResult(idRequest,
      idResult)
    helper.testManagerActor ! MediumInfoParserActor.ParseMediumInfoResult(infoRequest,
      infoResult)
    helper.simulateIDCalculatorActor()
    helper.simulateInfoParserActor()
    val mediaMap = helper.expectMediaAdded()
    mediaMap.keySet should not contain mid
  }

  it should "update the sequence number when a scan is canceled" in {
    val helper = new MediaManagerTestHelper
    helper.sendScanRequest()
    helper.simulateMediaScannerActor()
    helper.testManagerActor ! CloseRequest

    helper.simulateInfoParserActor()
    helper.simulateIDCalculatorActor()
    helper.expectNoMessageToUnionActor()
  }

  /**
   * A helper class combining data required for typical tests of a media
   * manager actor.
   *
   * This class also defines test audio data to be processed by the manager
   * actor. The data is divided into drives (which are the root directories to
   * be scanned) and media (directory sub structures with a description file
   * and audio data).
   *
   * @param childActorFunc an optional function for injecting child actors
   */
  private class MediaManagerTestHelper(
    childActorFunc: (ActorContext, Props) => Option[ActorRef] = (_, _) => None) {
    /** The root path. */
    private val root = Paths.get("root")

    /**
     * Generates a path from the root path and a list of path components.
     * @param components the path components
     * @return the resulting path
     */
    private def path(components: String*): Path = path(root, components: _*)

    /**
     * Generates a path from the given start path and a list of path components
     * which are appended.
     * @param start the start path component
     * @param components the path components
     * @return the resulting path
     */
    private def path(start: Path, components: String*): Path =
      components.foldLeft(start)(_.resolve(_))

    /**
     * Generates a list of paths of a given size in a given root directory.
     * @param dir the root directory
     * @param count the number of paths to generate
     * @return the list with the generated paths
     */
    private def pathList(dir: Path, count: Int): List[FileData] = {
      ((1 to count) map { i => FileData(path(dir, s"file$i.mp3").toString,
        1000 + i * 10) }).toList
    }

    /**
     * Generates a ''MediumID'' for the specified medium.
     * @param mediumNo the medium number
     * @param medPath the path to the medium
     * @return the medium ID
     */
    def definedMediumID(mediumNo: Int, medPath: Path): MediumID =
      MediumID.fromDescriptionPath(mediumSettings(medPath, mediumNo))

    /**
     * Generates a ''MediumIDData'' object for a medium.
     * @param mediumNo the medium number
     * @param medPath the path to the medium
     * @param content the content of this medium
     * @param scanResult the scan result
     * @return the corresponding ID data
     */
    private def idData(mediumNo: Int, medPath: Path, content: List[FileData],
                       scanResult: MediaScanResult): MediumIDData =
      MediumIDData(checksum = checksum(mediumNo),
        mediumID = definedMediumID(mediumNo, medPath),
        scanResult = scanResult, fileURIMapping = pathMapping(content))

    /**
     * Returns the checksum for the given medium.
     * @param mediumNo the medium number
     * @return the checksum for this medium
     */
    private def checksum(mediumNo: Int): String = s"Medium${mediumNo}_ID"

    /**
     * Generates a mapping from logic file URIs to physical paths.
     * @param content the list with media files
     * @return the path mapping
     */
    private def pathMapping(content: List[FileData]): Map[String, FileData] = {
      Map(content map (f => "path://" + f.path.toString -> f): _*)
    }

    /**
     * Generates the path of a medium with the given index on the given drive.
     * @param drive the root path of the drive
     * @param mediumNo the medium number
     * @return the root path of this medium
     */
    private def mediumPath(drive: Path, mediumNo: Int): Path =
      path(drive, s"medium$mediumNo")

    /**
     * Generates the path to a medium description file for the given medium
     * number.
     * @param mediumRoot the root path of the medium
     * @param mediumNo the medium number
     * @return the path to the description file for this medium
     */
    private def mediumSettings(mediumRoot: Path, mediumNo: Int): Path =
      path(mediumRoot, s"medium$mediumNo.settings")

    /**
     * Generates a settings data object for a medium.
     * @param mediumRoot the root path to the medium
     * @param mediumNo the medium number
     * @return the settings data object for this medium
     */
    private def settingsData(mediumRoot: Path, mediumNo: Int): MediumInfo =
      MediumInfo(name = s"Medium $mediumNo", description = s"Medium description $mediumNo",
        mediumID = definedMediumID(mediumNo, mediumRoot), orderMode = "", orderParams = "",
        checksum = checksum(mediumNo))

    /** Root of the first drive. */
    val Drive1Root: Path = path("drive1")

    /** Other files on drive 1. */
    val Drive1OtherFiles: List[FileData] = pathList(path(Drive1Root, "other"), 4)

    /** Root path of medium 1. */
    val Medium1Path: Path = mediumPath(Drive1Root, 1)

    /** Description file for medium 1. */
    val Medium1Desc: Path = mediumSettings(Medium1Path, 1)

    /** Content of the first medium. */
    val Medium1Content: List[FileData] = pathList(path(Medium1Path, "data"), 5)

    /** Settings data for medium 1. */
    val Medium1SettingsData: MediumInfo = settingsData(Medium1Path, 1)

    /** Root path of medium 2. */
    val Medium2Path: Path = mediumPath(Drive1Root, 2)

    /** Description file for medium 2. */
    val Medium2Desc: Path = mediumSettings(Medium2Path, 2)

    /** Content of medium 2. */
    val Medium2Content: List[FileData] = pathList(path(Medium2Path, "audio"), 8)

    /** Settings data for medium 2. */
    val Medium2SettingsData: MediumInfo = settingsData(Medium2Path, 2)

    /** Root of the second drive. */
    val Drive2Root: Path = path("drive2")

    /** Root path of medium 3. */
    val Medium3Path: Path = mediumPath(Drive2Root, 3)

    /** Description file for medium 3. */
    val Medium3Desc: Path = mediumSettings(Medium3Path, 3)

    /** Content of medium 3. */
    val Medium3Content: List[FileData] = pathList(path(Medium3Path, "music"), 16)

    /** Settings data for medium 3. */
    val Medium3SettingsData: MediumInfo = settingsData(Medium3Path, 3)

    /** Root of the third drive. */
    val Drive3Root: Path = path("3rdDrive")

    /** Drive 3 only has other files without a medium description. */
    val Drive3OtherFiles: List[FileData] = pathList(path(Drive3Root, "myMusic"), 32)

    /** The scan result for drive 1. */
    val Drive1 = MediaScanResult(Drive1Root, Map(
      MediumID.fromDescriptionPath(Medium1Desc) -> Medium1Content,
      MediumID.fromDescriptionPath(Medium2Desc) -> Medium2Content,
      MediumID(Drive1Root.toString, None, ArchiveComponentID) -> Drive1OtherFiles))

    /** Scan result for drive 2. */
    val Drive2 = MediaScanResult(Drive2Root, Map(MediumID.fromDescriptionPath(Medium3Desc) ->
      Medium3Content))

    /** Scan result for drive 3. */
    val Drive3 = MediaScanResult(Drive3Root,
      Map(MediumID(Drive3Root.toString, None, ArchiveComponentID) -> Drive3OtherFiles))

    /** ID data for medium 1. */
    val Medium1IDData: MediumIDData = idData(1, Medium1Path, Medium1Content, Drive1)

    /** ID data for other files found on drive 1. */
    val Drive1OtherIDData = MediumIDData("Other1", MediumID(Drive1Root.toString, None), Drive1,
      pathMapping(pathList(Drive1Root, 8)))

    /** ID data for medium 2. */
    val Medium2IDData: MediumIDData = idData(2, Medium2Path, Medium2Content, Drive1)

    /** ID data for medium 3. */
    val Medium3IDData: MediumIDData = idData(3, Medium3Path, Medium3Content, Drive2)

    /** ID data for other files found on drive 3. */
    val Drive3OtherIDData = MediumIDData("Other-3", MediumID(Drive3Root.toString, None), Drive3,
      pathMapping(Drive3OtherFiles))

    /** A sequence with the test root paths to be scanned. */
    val RootPaths = List(Drive1Root.toString, Drive2Root.toString, Drive3Root.toString)

    /**
     * A map with messages that are expected by collaboration actors and
     * their corresponding responses.
     */
    private val ActorMessages = Map[Any, Any](scanRequestMapping(Drive1Root, Drive1),
      scanRequestMapping(Drive2Root, Drive2),
      scanRequestMapping(Drive3Root, Drive3),
      idRequestMapping(Medium1Path, definedMediumID(1, Medium1Path), Drive1,
        Medium1Content, Medium1IDData),
      idRequestMapping(Medium2Path, definedMediumID(2, Medium2Path), Drive1,
        Medium2Content, Medium2IDData),
      idRequestMapping(Medium3Path, definedMediumID(3, Medium3Path), Drive2,
        Medium3Content, Medium3IDData),
      idRequestMapping(Drive1Root, MediumID(Drive1Root.toString, None, ArchiveComponentID),
        Drive1, Drive1OtherFiles, Drive1OtherIDData),
      idRequestMapping(Drive3Root, MediumID(Drive3Root.toString, None, ArchiveComponentID),
        Drive3, Drive3OtherFiles, Drive3OtherIDData),
      mediumInfoRequestMapping(Medium1Desc, Medium1SettingsData.mediumID,
        Medium1SettingsData.copy(checksum = "")),
      mediumInfoRequestMapping(Medium2Desc, Medium2SettingsData.mediumID,
        Medium2SettingsData.copy(checksum = "")),
      mediumInfoRequestMapping(Medium3Desc, Medium3SettingsData.mediumID,
        Medium3SettingsData.copy(checksum = "")))

    /**
     * A map for storing actors created by the test child actor factory. Each
     * time a child actor is requested, a ''TestProbe'' is created and stored
     * in this map for the corresponding actor class.
     */
    private var probes = createTestProbesMap()

    /** The mock for the configuration passed to the actor. */
    private val actorConfig = createActorConfig()

    /** A test probe representing the media manager actor. */
    val metaDataManagerActor = TestProbe()

    /** A test probe representing the media union actor. */
    val mediaUnionActor = TestProbe()

    /** The actor used for tests. */
    lazy val testManagerActor: TestActorRef[MediaManagerActor] = createTestActor()

    /** Counter for close request handling. */
    private val closeRequestCount = new AtomicInteger

    /** Counter for handling of completed close requests. */
    private val closeCompleteCount = new AtomicInteger

    /** Keeps track on the sequence numbers encountered. */
    private val seqNumbers = new AtomicReference(Set.empty[Int])

    /**
     * Executes a request for scanning media data on the test actor. This
     * method sends a ''ScanMedia'' message to the test actor and simulates the
     * reactions of collaborating actors.
     * @return a reference to the test actor
     */
    def scanMedia(): ActorRef = {
      sendScanRequest()
      simulateCollaboratingActors()
      testManagerActor
    }

    /**
     * Sends a ''ScanMedia'' request to the test actor (without simulating the
     * responses of collaborating actors).
     * @return a reference to the test actor
     */
    def sendScanRequest(): ActorRef = {
      testManagerActor ! ScanAllMedia
      testManagerActor
    }

    /**
     * Checks whether the map with available media contains all media
     * for which description files are provided.
     * @param media the map with available media
     * @return the same passed in map
     */
    def checkMediaWithDescriptions(media: Map[MediumID, MediumInfo]):
    Map[MediumID, MediumInfo] = {
      media(definedMediumID(1, Medium1Path)) should be(Medium1SettingsData)
      media(definedMediumID(2, Medium2Path)) should be(Medium2SettingsData)
      media(definedMediumID(3, Medium3Path)) should be(Medium3SettingsData)
      media
    }

    /**
      * Expects that media data was added to the union media actor. The map
      * with actual media data is returned.
      *
      * @return the map with media data
      */
    def expectMediaAdded(): Map[MediumID, MediumInfo] = {
      val addMedia = mediaUnionActor.expectMsgType[AddMedia]
      addMedia.archiveCompID should be(ArchiveComponentID)
      addMedia.optCtrlActor shouldBe 'empty
      addMedia.media
    }

    /**
      * Checks that no message has been sent to the union media manager.
      *
      * @return this test helper
      */
    def expectNoMessageToUnionActor(): MediaManagerTestHelper = {
      mediaUnionActor.expectNoMsg(1.second)
      this
    }

    /**
      * Expects that a message about a removed archive component is sent to the
      * media union actor.
      *
      * @return this test helper
      */
    def expectRemoveComponentMessage(): MediaManagerTestHelper = {
      mediaUnionActor.expectMsg(ArchiveComponentRemoved(ArchiveComponentID))
      this
    }

    /**
      * Sends a confirmation about a removed archive component to the test
      * actor.
      *
      * @param compID the archive component ID to be passed
      * @return this test helper
      */
    def answerRemoveComponentMessage(compID: String = ArchiveComponentID):
    MediaManagerTestHelper = {
      testManagerActor receive RemovedArchiveComponentProcessed(compID)
      this
    }

    /**
      * Checks whether the meta data actor received the expected messages
      * during a scan operation.
      *
      * @return this test helper
      */
    def checkMetaDataMessages(): MediaManagerTestHelper = {
      metaDataManagerActor.expectMsg(MediaScanStarts)
      for (_ <- 1 to actorConfig.mediaRootPaths.size) {
        metaDataManagerActor.expectMsgType[EnhancedMediaScanResult]
      }
      metaDataManagerActor.expectMsgType[AvailableMedia]
      expectNoMoreMessage(metaDataManagerActor)
      this
    }

    /**
      * Prepares the mock for the actor configuration to return different root
      * paths for multiple scan operations.
      *
      * @param paths1 the first return value
      * @param paths  further paths to be returned for additional scans
      * @return this test helper
      */
    def configureRootPathsForScans(paths1: Set[String], paths: Set[String]*):
    MediaManagerTestHelper = {
      when(actorConfig.mediaRootPaths).thenReturn(paths1, paths: _*)
      this
    }

    /**
     * Returns a list with test probes representing child actors of the given
     * class. This method can be used to query the child actors created by the
     * test actor instance.
     * @param t the class tag of the requested child actor type
     * @tparam T the child actor type
     * @return a list with the child actors of this type
     */
    def probesOfType[T](implicit t: ClassTag[T]): List[TestProbe] =
      probesOfActorClass(t.runtimeClass)

    /**
      * Returns a list with information about test probes created fro the
      * specified child actor class.
      *
      * @param t the class tag of the requested child actor type
      * @tparam T the child actor type
      * @return a list with data about these test probes
      */
    def probeDataOfType[T](implicit t: ClassTag[T]): List[ProbeData] =
      probeDataOfActorClass(t.runtimeClass)

    /**
     * Returns a list with the test probes created for child actors of the
     * given actor class. This method expects the desired child actor class
     * directly as argument.
     * @param actorClass the child actor class
     * @return a list with the child actors of this class
     */
    def probesOfActorClass(actorClass: Class[_]): List[TestProbe] =
      probeDataOfActorClass(actorClass) map(_.probe)

    /**
      * Returns a list with information about test probes created for the
      * specified child actor class.
      *
      * @param actorClass the child actor class
      * @return a list with data about these test probes
      */
    def probeDataOfActorClass(actorClass: Class[_]): List[ProbeData] =
      probes.getOrElse(actorClass, Nil)

    /**
      * Returns the test probe for the media scanner actor.
      *
      * @return the probe for the media scanner actor
      */
    def mediaScannerProbe: TestProbe = probesOfType[MediaScannerActor].head

    /**
      * Resets this test helper, so that another scan operation can be
      * started.
      *
      * @return this test helper
      */
    def reset(): MediaManagerTestHelper = {
      seqNumbers set Set.empty
      resetProbes()
    }

    /**
      * Resets the map with test probes. Note: Some actors have to be
      * treated in a special way because they are created in preStart(); they
      * must not be created again for a 2nd run.
      * @return this test helper
      */
    private def resetProbes(): MediaManagerTestHelper = {
      val reusedClasses = List(ClsDirScanner, ClsInfoParser)
      val reusedData = reusedClasses map probes
      probes = createTestProbesMap()
      reusedClasses.zip(reusedData).foreach(t => probes += t._1 -> t._2)
      this
    }

    /**
      * Returns the number of handled close requests.
      *
      * @return the number of close requests handled by the test actor
      */
    def numberOfCloseRequests: Int = closeRequestCount.get()

    /**
      * Returns the number of completed close operations.
      *
      * @return the number of completed close operations
      */
    def numberOfCompletedCloseOps: Int = closeCompleteCount.get()

    /**
      * Returns the test probe for the download manager.
      *
      * @return the test probe for the download manager actor
      */
    def downloadManager: TestProbe = probesOfActorClass(ClsDownloadManagerActor).head

    /**
     * Creates a ''TestProbe'' that simulates a child actor and adds it to the
     * map of child actors.
     * @param props the ''Props'' for the child actor
     * @return the newly created probe
     */
    private def createProbeForChildActor(props: Props): TestProbe = {
      val probe = TestProbe()
      val probeList = probes.getOrElse(props.actorClass(), Nil)
      probes += props.actorClass() -> (ProbeData(probe, props) :: probeList)
      probe
    }

    /**
     * Checks the arguments defined in the given ''Props'' object. This is
     * used to check whether a child actor is created with the expected
     * helper object.
     * @param props the ''Props'' object to be checked
     * @return the checked ''Props'' object
     */
    private def checkArgs(props: Props): Props = {
      if (ClsInfoParser == props.actorClass()) {
        props.args should have length 2
        props.args.head shouldBe a[MediumInfoParser]
        props.args(1) should be(InfoSizeLimit)
      } else {
        expectedArgForActorClass(props) match {
          case Some(expectedArgs) =>
            props.args should contain theSameElementsAs expectedArgs
          case _ =>
        }
      }
      props
    }

    /**
     * Returns the arguments to check for the given ''Props'' object. For
     * the different child actor classes specific arguments are expected.
     * @param props the ''Props'' object
     * @return an Iterable with the arguments to be checked
     */
    private def expectedArgForActorClass(props: Props): Option[Iterable[Any]] =
      props.actorClass() match {
        case ClsDirScanner =>
          Some(List(ExcludedExtensions))

        case ClsIDCalculator =>
          Some(List(testManagerActor.underlyingActor.idCalculator))

        case ClsMediaReaderActor =>
          Some(List(probesOfType[FileReaderActor].head.ref,
            testManagerActor.underlyingActor.id3Extractor))

        case ClsDownloadManagerActor =>
          Some(List(actorConfig.downloadConfig))

        case _ => None
      }

    /**
     * Simulates the communication with all collaborating child actors. For all
     * test probes created for child actor types messages of a specific type
     * are expected, and corresponding responses are generated.
     */
    def simulateCollaboratingActors(): Unit = {
      simulateMediaScannerActor()
      simulateIDCalculatorActor()
      simulateInfoParserActor()
    }

    /**
      * Simulates the communication with the ID calculator actor.
      */
    def simulateIDCalculatorActor(): Unit = {
      simulateCollaboratingActorsOfType[MediumIDCalculatorActor.
      CalculateMediumID](classOf[MediumIDCalculatorActor])
    }

    /**
      * Simulates the communication with the media scanner actor. There is
      * only a single actor instance handling all scan requests.
      */
    def simulateMediaScannerActor(): Unit = {
      val NumberOfMessages = 3
      simulateCollaboratingActorForMultiMessages[MediaScannerActor.ScanPath](ClsDirScanner,
        NumberOfMessages)
    }

    /**
     * Simulates the communication with the info parser actor. There is
      * only a single actor instance handling all parse requests.
     */
    def simulateInfoParserActor(): Unit = {
      val NumberOfMessages = 3
      simulateCollaboratingActorForMultiMessages[MediumInfoParserActor.ParseMediumInfo](
        ClsInfoParser, NumberOfMessages)
    }

    /**
      * Returns the sequence number that was used during the last scan
      * operation.
      *
      * @return the sequence number
      */
    def seqNo: Int = {
      val seqNos = seqNumbers.get()
      seqNos should have size 1
      seqNos.head
    }

    /**
      * Simulates the communication with a collaborating actor for which only
      * one instance exists which has to be invoked with multiple messages.
      *
      * @param actorCls the actor class
      * @param count    the number of expected messages
      * @param t        the class tag for the message type
      * @tparam T the message type
      */
    private def simulateCollaboratingActorForMultiMessages[T](actorCls: Class[_], count: Int)
                                                             (implicit t: ClassTag[T]) {
      for (_ <- 1 to count) {
        simulateCollaboratingActorsOfType[T](actorCls)
      }
    }

    /**
     * Simulates the communication with collaborating actors of a given actor
     * class. This method determines all test probes of the given actor class.
     * For each probe a message of the given type is expected, and - based on
     * the map with actor messages - a corresponding response is sent to the
     * test actor. Things become a bit tricky because sequence numbers have to
     * be handled.
     * @param actorCls the actor class
     * @param t the class tag for the message type
     * @tparam T the message type
     */
    private def simulateCollaboratingActorsOfType[T](actorCls: Class[_])(implicit t: ClassTag[T])
    : Unit = {
      probes(actorCls) foreach { p =>
        val msg = p.probe.expectMsgType[T](t)
        val seqNo = extractSeqNo(msg)
        addSeqNo(seqNo)
        val msg2 = updateSeqNo(msg, 0)
        testManagerActor.tell(updateSeqNo(ActorMessages(msg2), seqNo), p.probe.ref)
      }
    }

    /**
     * Creates a test actor instance as a test reference.
     * @return the test reference
     */
    private def createTestActor(): TestActorRef[MediaManagerActor] = {
      TestActorRef[MediaManagerActor](Props(
        new MediaManagerActor(actorConfig, metaDataManagerActor.ref,
          mediaUnionActor.ref)
        with ChildActorFactory with CloseSupport {
        override def createChildActor(p: Props): ActorRef = {
          childActorFunc(context, p) getOrElse createProbeForChildActor(checkArgs(p)).ref
        }

          /**
            * Checks parameters and records this invocation.
            */
          override def onCloseRequest(subject: ActorRef, deps: => Iterable[ActorRef], target:
          ActorRef, factory: ChildActorFactory, conditionState: => Boolean): Boolean = {
            subject should be(testManagerActor)
            deps should contain only metaDataManagerActor.ref
            target should be(testActor)
            conditionState shouldBe true
            factory should be(this)
            closeRequestCount.incrementAndGet()
            true
          }

          /**
            * Records this invocation.
            */
          override def onCloseComplete(): Unit = {
            closeCompleteCount.incrementAndGet()
          }
        }))
    }

    /**
      * Creates a configuration object for the test actor. This configuration
      * contains some more properties than the basic configuration returned by
      * ''createConfiguration()''.
      *
      * @return the configuration for the test actor
      */
    private def createActorConfig(): MediaArchiveConfig = {
      val config = createConfiguration()
      val roots = RootPaths.toSet
      when(config.mediaRootPaths).thenReturn(roots)
      config
    }

    /**
     * Creates the map which stores the test probes used by this test helper
     * class. Some default values are set for typical actor classes. (This is
     * done to make it possible to inject test actors for the default probes.
     * In this case, the map still needs to contain empty lists.)
     * @return the initial map with test probes
     */
    private def createTestProbesMap(): collection.mutable.Map[Class[_], List[ProbeData]] =
      collection.mutable.Map(ClsFileLoaderActor -> Nil,
        MediaManagerActorSpec.ClsInfoParser -> Nil)

    /**
      * Updates the set with sequence numbers.
      *
      * @param n the number to be added
      */
    @tailrec private def addSeqNo(n: Int): Unit = {
      val currentSet = seqNumbers.get()
      val nextSet = currentSet + n
      if (!seqNumbers.compareAndSet(currentSet, nextSet)) {
        addSeqNo(n)
      }
    }
  }

}
