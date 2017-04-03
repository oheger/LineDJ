package de.oliver_heger.linedj.archive.media

import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}

import akka.actor._
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.RecordingSchedulerSupport
import de.oliver_heger.linedj.RecordingSchedulerSupport.SchedulerInvocation
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.mp3.ID3HeaderExtractor
import de.oliver_heger.linedj.archivecommon.parser.MediumInfoParser
import de.oliver_heger.linedj.io._
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.shared.archive.union.{AddMedia, ArchiveComponentRemoved, RemovedArchiveComponentProcessed}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.{anyLong, eq => argEq}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

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

  /** A special test message sent to actors. */
  private val TestMessage = new Object

  /** The initial delay for reader actor timeout checks. */
  private val ReaderCheckDelay = 10.minutes

  /** The interval for reader actor timeout checks. */
  private val ReaderCheckInterval = 5.minutes

  /** The set with excluded file extensions. */
  private val ExcludedExtensions = Set("TXT", "JPG")

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
    val config = mock[MediaArchiveConfig]
    when(config.readerTimeout).thenReturn(60.seconds)
    when(config.readerCheckInterval).thenReturn(ReaderCheckInterval)
    when(config.readerCheckInitialDelay).thenReturn(ReaderCheckDelay)
    when(config.excludedFileExtensions).thenReturn(ExcludedExtensions)
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
    manager.underlyingActor.directoryScanner shouldBe a[MediaScanner]
    manager.underlyingActor.directoryScanner.excludedExtensions should be(ExcludedExtensions)
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
    for {cls <- List[Class[_]](classOf[MediaScannerActor], classOf[MediumIDCalculatorActor],
      classOf[MediumInfoParserActor])
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
   * @param optMapping an optional reader actor mapping
   * @return the test helper
   */
  private def prepareHelperForScannedMedia(optMapping: Option[MediaReaderActorMapping] = None):
  MediaManagerTestHelper = {
    val helper = new MediaManagerTestHelper(optMapping = optMapping)
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
    val readerProbe = helper.probesOfType[MediaFileReaderActor].head
    val readRequest = FileReaderActor.ReadData(32)
    response.contentReader ! readRequest
    readerProbe.expectMsg(readRequest)
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

    val readerProbe = helper.probesOfType[MediaFileReaderActor].head
    readerProbe.expectMsg(ChannelHandler.InitFile(asPath(file.path)))
    response.contentReader should be(readerProbe.ref)
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

  it should "handle IO operation exceptions sent from a file loader actor" in {
    val helper = new MediaManagerTestHelper(childActorFunc = { (ctx, props) =>
      if (props.actorClass() == ClsFileLoaderActor) {
        Some(ctx.actorOf(Props(new Actor {
          override def receive: Receive = {
            case FileLoaderActor.LoadFile(p) =>
              sender ! FileOperationActor.IOOperationError(p, new Exception("TestException"))
          }
        })))
      } else None
    })

    helper.scanMedia()
    val media = helper.expectMediaAdded()
    media(helper.definedMediumID(1, helper.Medium1Path)).name should be(MediumInfoParserActor
      .undefinedMediumInfo.name)
  }

  it should "create a default reader actor mapping" in {
    val actor = TestActorRef[MediaManagerActor](MediaManagerActor(createConfiguration(),
      testActor, TestProbe().ref))
    actor.underlyingActor.readerActorMapping shouldBe a[MediaReaderActorMapping]
  }

  /**
   * Obtains the mapping for the last created reader actor. This method returns
   * a tuple with the test probes created for the media reader and its
   * underlying reader.
   * @param helper the test helper
   * @return a tuple with the test probes created for the reader actors
   */
  private def fetchReaderActorMapping(helper: MediaManagerTestHelper): (Option[TestProbe], TestProbe) = {
    val procReader = helper.probesOfType[MediaFileReaderActor].headOption
    val actReader = helper.probesOfType[FileReaderActor].head
    (procReader, actReader)
  }

  it should "add newly created reader actors to the mapping" in {
    val mapping = mock[MediaReaderActorMapping]
    when(mapping.findReadersForClient(testActor)).thenReturn(List.empty)
    val helper = prepareHelperForScannedMedia(Some(mapping))
    helper.testManagerActor ! createRequestForExistingFile(helper)

    expectMsgType[MediumFileResponse].length should be > 0L
    val (optProcReader, actReader) = fetchReaderActorMapping(helper)
    val captor = ArgumentCaptor forClass classOf[Long]
    verify(mapping).add(argEq((optProcReader.get.ref, Some(actReader.ref))),
      argEq(testActor), captor.capture())
    val timestamp = captor.getValue
    Duration(System.currentTimeMillis() - timestamp, MILLISECONDS) should be <= 10.seconds
  }

  it should "respect the withMetaData flag in a media file request" in {
    val mapping = mock[MediaReaderActorMapping]
    when(mapping.findReadersForClient(testActor)).thenReturn(List.empty)
    val helper = prepareHelperForScannedMedia(Some(mapping))
    helper.testManagerActor ! createRequestForExistingFile(helper, withMetaData = true)

    val response = expectMsgType[MediumFileResponse]
    val (optProcReader, actReader) = fetchReaderActorMapping(helper)
    response.contentReader should be(actReader.ref)
    optProcReader shouldBe 'empty
    verify(mapping).add(argEq((actReader.ref, None)), argEq(testActor), anyLong())
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
    val readerProbe = helper.probesOfType[MediaFileReaderActor].head
    readerProbe.expectMsg(ChannelHandler.InitFile(asPath(targetFile.path)))
  }

  it should "stop the underlying reader actor when the processing reader is stopped" in {
    val helper = prepareHelperForScannedMedia()
    helper.testManagerActor ! createRequestForExistingFile(helper)
    expectMsgType[MediumFileResponse]
    val (optProcReader, actReader) = fetchReaderActorMapping(helper)

    val watcher = TestProbe()
    watcher watch actReader.ref
    system stop optProcReader.get.ref
    watcher.expectMsgType[Terminated].actor should be (actReader.ref)
  }

  it should "deal with undefined options when stopping a file reader actor" in {
    val mapping = new MediaReaderActorMapping
    val helper = prepareHelperForScannedMedia(optMapping = Some(mapping))
    helper.testManagerActor ! createRequestForExistingFile(helper, withMetaData = true)
    expectMsgType[MediumFileResponse]
    val (_, actReader) = fetchReaderActorMapping(helper)
    mapping hasActor actReader.ref shouldBe true

    system stop actReader.ref
    awaitCond(!mapping.hasActor(actReader.ref))
  }

  it should "stop the reader actor when the client actor dies" in {
    val helper = prepareHelperForScannedMedia()
    val client = TestProbe()
    helper.testManagerActor.tell(createRequestForExistingFile(helper), client.ref)
    client.expectMsgType[MediumFileResponse]
    val (optProcReader, _) = fetchReaderActorMapping(helper)

    val watcher = TestProbe()
    watcher watch optProcReader.get.ref
    system stop client.ref
    watcher.expectMsgType[Terminated].actor should be (optProcReader.get.ref)
  }

  it should "do only required termination handling when a client actor dies" in {
    val helper = prepareHelperForScannedMedia()
    helper.expectMediaAdded()
    val client = TestProbe()
    helper.testManagerActor.tell(createRequestForExistingFile(helper), client.ref)

    system stop client.ref
    helper.mediaUnionActor.expectNoMsg(1.second)
  }

  it should "stop watching a client actor if there are no more read operations" in {
    val helper = prepareHelperForScannedMedia()
    helper.expectMediaAdded()
    val client = TestProbe()
    helper.testManagerActor.tell(createRequestForExistingFile(helper), client.ref)
    client.expectMsgType[MediumFileResponse]
    val (optProcReader, reader) = fetchReaderActorMapping(helper)
    system stop optProcReader.get.ref
    val watcher = TestProbe()
    watcher watch reader.ref
    watcher.expectMsgType[Terminated]

    system stop client.ref
    helper.mediaUnionActor.expectNoMsg(1.second)
  }

  it should "handle multiple file requests when watching a client" in {
    val helper = prepareHelperForScannedMedia()
    helper.expectMediaAdded()
    val client = TestProbe()
    helper.testManagerActor.tell(createRequestForExistingFile(helper), client.ref)
    client.expectMsgType[MediumFileResponse]
    val (optProcReader, reader) = fetchReaderActorMapping(helper)
    helper.testManagerActor.tell(createRequestForExistingFile(helper), client.ref)
    client.expectMsgType[MediumFileResponse]
    val (optProcReader2, _) = fetchReaderActorMapping(helper)
    system stop optProcReader.get.ref
    val watcher = TestProbe()
    watcher watch reader.ref
    watcher.expectMsgType[Terminated]

    system stop client.ref
    watcher watch optProcReader2.get.ref
    watcher.expectMsgType[Terminated]
  }

  it should "stop reader actors that timed out" in {
    val mapping = new MediaReaderActorMapping
    val procReader1, fileReader1, procReader2, fileReader2, watcher = TestProbe()
    val now = System.currentTimeMillis()
    mapping.add(procReader1.ref -> Some(fileReader1.ref), testActor, now - 65 * 1000)
    mapping.add(procReader2.ref -> Some(fileReader2.ref), testActor, now)
    val helper = new MediaManagerTestHelper(optMapping = Some(mapping))
    watcher watch procReader1.ref
    watcher watch procReader2.ref

    helper.testManagerActor ! MediaManagerActor.CheckReaderTimeout
    watcher.expectMsgType[Terminated].actor should be (procReader1.ref)
    expectNoMoreMessage(watcher)
  }

  it should "allow updating active reader actors" in {
    val mapping = new MediaReaderActorMapping
    val procReader, fileReader, watcher = TestProbe()
    mapping.add(procReader.ref -> Some(fileReader.ref), testActor, 0L)
    val helper = new MediaManagerTestHelper(optMapping = Some(mapping))
    watcher watch procReader.ref

    helper.testManagerActor ! ReaderActorAlive(procReader.ref, null)
    helper.testManagerActor ! MediaManagerActor.CheckReaderTimeout
    expectNoMoreMessage(watcher)
  }

  it should "check for timed out reader actors periodically" in {
    val helper = new MediaManagerTestHelper
    val expectedReceiver = helper.testManagerActor

    val invocation = RecordingSchedulerSupport.expectInvocation(helper.schedulerQueue)
    invocation.initialDelay should be(ReaderCheckDelay)
    invocation.interval should be (ReaderCheckInterval)
    invocation.receiver should be(expectedReceiver)
    invocation.message should be(MediaManagerActor.CheckReaderTimeout)
  }

  it should "cancel periodic reader checks when it is stopped" in {
    val helper = new MediaManagerTestHelper
    val probe = TestProbe()
    helper.testManagerActor ! ReaderActorAlive(probe.ref, null)

    system stop helper.testManagerActor
    val invocation = RecordingSchedulerSupport.expectInvocation(helper.schedulerQueue)
    awaitCond(invocation.cancellable.isCancelled)
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
    helper.checkMetaDataMessages().resetProbes()

    helper.scanMedia()
    helper.answerRemoveComponentMessage()
    helper.checkMetaDataMessages()
  }

  it should "handle a close request" in {
    val helper = new MediaManagerTestHelper

    helper.testManagerActor ! CloseRequest
    helper.numberOfCloseRequests should be(1)
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

    helper.resetProbes().scanMedia()
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
    helper.resetProbes().scanMedia()
    helper.answerRemoveComponentMessage().checkMetaDataMessages()

    helper.answerRemoveComponentMessage()
    expectNoMoreMessage(helper.metaDataManagerActor)
  }

  it should "clear the list of pending messages when receiving a close request" in {
    val helper = new MediaManagerTestHelper
    helper.scanMedia()
    helper.checkMetaDataMessages().expectMediaAdded()
    helper.resetProbes().scanMedia()

    helper.testManagerActor ! CloseRequest
    helper.answerRemoveComponentMessage()
    expectNoMoreMessage(helper.metaDataManagerActor)
  }

  it should "ignore a remove confirmation for another archive component" in {
    val helper = new MediaManagerTestHelper
    helper.scanMedia()
    helper.checkMetaDataMessages().expectMediaAdded()
    helper.resetProbes().scanMedia()

    helper.answerRemoveComponentMessage("some other archive component")
    expectNoMoreMessage(helper.metaDataManagerActor)
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
   * @param optMapping an optional mapping for reader actors
   * @param childActorFunc an optional function for injecting child actors
   */
  private class MediaManagerTestHelper(optMapping: Option[MediaReaderActorMapping] = None,
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

    /**
     * Generates a request for an ID calculation.
     * @param medPath the medium path
     * @param mediumNo the medium number
     * @param scanResult the scan result
     * @param content the medium content
     * @return the request message
     */
    private def calcRequest(medPath: Path, mediumNo: Int, scanResult: MediaScanResult, content:
    Seq[FileData]): MediumIDCalculatorActor.CalculateMediumID =
      MediumIDCalculatorActor.CalculateMediumID(medPath, definedMediumID(mediumNo, medPath),
        scanResult, content)

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

    /** Binary content of the description file for medium 1. */
    val Medium1BinaryDesc = new Array[Byte](1)

    /** Root path of medium 2. */
    val Medium2Path: Path = mediumPath(Drive1Root, 2)

    /** Description file for medium 2. */
    val Medium2Desc: Path = mediumSettings(Medium2Path, 2)

    /** Content of medium 2. */
    val Medium2Content: List[FileData] = pathList(path(Medium2Path, "audio"), 8)

    /** Settings data for medium 2. */
    val Medium2SettingsData: MediumInfo = settingsData(Medium2Path, 2)

    /** Binary content of the description file for medium 2. */
    val Medium2BinaryDesc = new Array[Byte](2)

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

    /** Binary content of the description file for medium 3. */
    val Medium3BinaryDesc = new Array[Byte](3)

    /** Root of the third drive. */
    val Drive3Root: Path = path("3rdDrive")

    /** Drive 3 only has other files without a medium description. */
    val Drive3OtherFiles: List[FileData] = pathList(path(Drive3Root, "myMusic"), 32)

    /** The scan result for drive 1. */
    val Drive1 = MediaScanResult(Drive1Root, Map(
      MediumID.fromDescriptionPath(Medium1Desc) -> Medium1Content,
      MediumID.fromDescriptionPath(Medium2Desc) -> Medium2Content,
      MediumID(Drive1Root.toString, None) -> Drive1OtherFiles))

    /** Scan result for drive 2. */
    val Drive2 = MediaScanResult(Drive2Root, Map(MediumID.fromDescriptionPath(Medium3Desc) ->
      Medium3Content))

    /** Scan result for drive 3. */
    val Drive3 = MediaScanResult(Drive3Root, Map(MediumID(Drive3Root.toString, None) ->
      Drive3OtherFiles))

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
    private val ActorMessages = Map[Any, Any](MediaScannerActor.ScanPath(Drive1Root) -> Drive1,
      MediaScannerActor.ScanPath(Drive2Root) -> Drive2,
      MediaScannerActor.ScanPath(Drive3Root) -> Drive3,
      calcRequest(Medium1Path, 1, Drive1, Medium1Content) -> Medium1IDData,
      calcRequest(Medium2Path, 2, Drive1, Medium2Content) -> Medium2IDData,
      calcRequest(Medium3Path, 3, Drive2, Medium3Content) -> Medium3IDData,
      MediumIDCalculatorActor.CalculateMediumID(Drive1Root, MediumID(Drive1Root.toString, None),
        Drive1, Drive1OtherFiles) -> Drive1OtherIDData,
      MediumIDCalculatorActor.CalculateMediumID(Drive3Root, MediumID(Drive3Root.toString, None),
        Drive3, Drive3OtherFiles) -> Drive3OtherIDData,
      FileLoaderActor.LoadFile(Medium1Desc) -> FileLoaderActor.FileContent(Medium1Desc,
        Medium1BinaryDesc),
      FileLoaderActor.LoadFile(Medium2Desc) -> FileLoaderActor.FileContent(Medium2Desc,
        Medium2BinaryDesc),
      FileLoaderActor.LoadFile(Medium3Desc) -> FileLoaderActor.FileContent(Medium3Desc,
        Medium3BinaryDesc),
      MediumInfoParserActor.ParseMediumInfo(Medium1BinaryDesc, Medium1SettingsData.mediumID) ->
        Medium1SettingsData.copy(checksum = ""),
      MediumInfoParserActor.ParseMediumInfo(Medium2BinaryDesc, Medium2SettingsData.mediumID) ->
        Medium2SettingsData.copy(checksum = ""),
      MediumInfoParserActor.ParseMediumInfo(Medium3BinaryDesc, Medium3SettingsData.mediumID) ->
        Medium3SettingsData.copy(checksum = ""))

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

    /** A queue for storing scheduler invocations. */
    val schedulerQueue = new LinkedBlockingQueue[RecordingSchedulerSupport.SchedulerInvocation]

    /** The actor used for tests. */
    lazy val testManagerActor: TestActorRef[MediaManagerActor] = createTestActor()

    /** Counter for close request handling. */
    private val closeRequestCount = new AtomicInteger

    /** Counter for handling of completed close requests. */
    private val closeCompleteCount = new AtomicInteger

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
     * Returns a list with the test probes created for child actors of the
     * given actor class. This method expects the desired child actor class
     * directly as argument.
     * @param actorClass the child actor class
     * @return a list with the child actors of this class
     */
    def probesOfActorClass(actorClass: Class[_]): List[TestProbe] =
      probes.getOrElse(actorClass, Nil)

    /**
      * Resets the map with test probes. Note: The file loader actor has to be
      * treated in a special way because it is created in preStart(); so it is
      * not created again on a second run.
      * @return this test helper
      */
    def resetProbes(): MediaManagerTestHelper = {
      val fileLoaderActorCls = FileLoaderActor().actorClass()
      val loaderActorData = probes(fileLoaderActorCls)
      probes = createTestProbesMap()
      probes += fileLoaderActorCls -> loaderActorData
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
     * Creates a ''TestProbe'' that simulates a child actor and adds it to the
     * map of child actors.
     * @param props the ''Props'' for the child actor
     * @return the newly created probe
     */
    private def createProbeForChildActor(props: Props): TestProbe = {
      val probe = TestProbe()
      val probeList = probes.getOrElse(props.actorClass(), Nil)
      probes += props.actorClass() -> (probe :: probeList)
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
      val expectedArgs = expectedArgForActorClass(props)
      props.args should contain theSameElementsAs expectedArgs
      props
    }

    /**
     * Returns the arguments to check for the given ''Props'' object. For
     * the different child actor classes specific arguments are expected.
     * @param props the ''Props'' object
     * @return an Iterable with the arguments to be checked
     */
    private def expectedArgForActorClass(props: Props): Iterable[Any] =
      props.actorClass() match {
        case MediaManagerActorSpec.ClsDirScanner =>
          Some(testManagerActor.underlyingActor.directoryScanner)

        case MediaManagerActorSpec.ClsIDCalculator =>
          Some(testManagerActor.underlyingActor.idCalculator)

        case MediaManagerActorSpec.ClsInfoParser =>
          Some(testManagerActor.underlyingActor.mediumInfoParser)

        case MediaManagerActorSpec.ClsMediaReaderActor =>
          List(probesOfType[FileReaderActor].head.ref, testManagerActor.underlyingActor.id3Extractor)

        case _ => None
      }

    /**
     * Simulates the communication with all collaborating child actors. For all
     * test probes created for child actor types messages of a specific type
     * are expected, and corresponding responses are generated.
     */
    def simulateCollaboratingActors(): Unit = {
      simulateCollaboratingActorsOfType[MediaScannerActor.ScanPath] (classOf[MediaScannerActor])
      simulateCollaboratingActorsOfType[MediumIDCalculatorActor.CalculateMediumID] (classOf[MediumIDCalculatorActor])
      simulateFileLoaderActor()
      simulateCollaboratingActorsOfType[MediumInfoParserActor.ParseMediumInfo] (classOf[MediumInfoParserActor])
    }

    /**
     * Simulates the communication with the file loader actor. This is slightly
     * different from other helper actors as only a single actor reference is
     * used. Therefore, this reference has to be triggered manually for each
     * expected message.
     */
    private def simulateFileLoaderActor(): Unit = {
      val NumberOfMessages = 3
      for (_ <- 1 to NumberOfMessages) {
        simulateCollaboratingActorsOfType[FileLoaderActor.LoadFile](ClsFileLoaderActor)
      }
    }

    /**
     * Simulates the communication with collaborating actors of a given actor
     * class. This method determines all test probes of the given actor class.
     * For each probe a message of the given type is expected, and - based on
     * the map with actor messages - a corresponding response is sent to the
     * test actor.
     * @param actorCls the actor class
     * @param t the class tag for the message type
     * @tparam T the message type
     */
    private def simulateCollaboratingActorsOfType[T](actorCls: Class[_])(implicit t: ClassTag[T])
    : Unit = {
      probes(actorCls) foreach { p =>
        val msg = p.expectMsgType[T](t)
        testManagerActor.tell(ActorMessages(msg), p.ref)
      }
    }

    /**
     * Creates a test actor instance as a test reference.
     * @return the test reference
     */
    private def createTestActor(): TestActorRef[MediaManagerActor] = {
      val mapping = optMapping getOrElse new MediaReaderActorMapping
      TestActorRef[MediaManagerActor](Props(
        new MediaManagerActor(actorConfig, metaDataManagerActor.ref,
          mediaUnionActor.ref, mapping)
        with ChildActorFactory with RecordingSchedulerSupport with CloseSupport {
        override def createChildActor(p: Props): ActorRef = {
          childActorFunc(context, p) getOrElse createProbeForChildActor(checkArgs(p)).ref
        }

        override val queue: BlockingQueue[SchedulerInvocation] = schedulerQueue

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
    private def createTestProbesMap(): collection.mutable.Map[Class[_], List[TestProbe]] =
      collection.mutable.Map(ClsFileLoaderActor -> Nil,
        MediaManagerActorSpec.ClsInfoParser -> Nil)
  }

}
