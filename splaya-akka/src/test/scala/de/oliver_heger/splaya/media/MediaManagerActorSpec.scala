package de.oliver_heger.splaya.media

import java.nio.file.{Path, Paths}
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}

import akka.actor._
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import de.oliver_heger.splaya.RecordingSchedulerSupport
import de.oliver_heger.splaya.RecordingSchedulerSupport.SchedulerInvocation
import de.oliver_heger.splaya.io.{ChannelHandler, FileLoaderActor, FileOperationActor, FileReaderActor}
import de.oliver_heger.splaya.media.MediaManagerActor.ScanMedia
import de.oliver_heger.splaya.mp3.ID3HeaderExtractor
import de.oliver_heger.splaya.playback.{AudioSourceDownloadResponse, AudioSourceID}
import de.oliver_heger.splaya.utils.ChildActorFactory
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.{eq => argEq}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.reflect.ClassTag

object MediaManagerActorSpec {
  /** Class for the directory scanner child actor. */
  val ClsDirScanner = classOf[DirectoryScannerActor]

  /** Class for the ID calculator child actor. */
  val ClsIDCalculator = classOf[MediumIDCalculatorActor]

  /** Class for the medium info parser child actor. */
  val ClsInfoParser = classOf[MediumInfoParserActor]

  /** Class for the media reader actor child actor. */
  val ClsMediaReaderActor = classOf[MediaFileReaderActor]

  /** A special test message sent to actors. */
  private val TestMessage = new Object

  /** The initial delay for reader actor timeout checks. */
  private val ReaderCheckDelay = 10.minutes

  /** The interval for reader actor timeout checks. */
  private val ReaderCheckInterval = 5.minutes

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

  def this() = this(ActorSystem("MediaManagerActorSpec",
    ConfigFactory.parseString(
      s"""splaya {
         |  media {
         |    readerTimeout = 60s
         |    readerCheckInterval = ${MediaManagerActorSpec.ReaderCheckInterval.toString()}
         |    readerCheckInitialDelay = ${MediaManagerActorSpec.ReaderCheckDelay.toString()}
         |  }
         |}
       """.stripMargin)))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  "A MediaManagerActor" should "create a correct Props object" in {
    val props = MediaManagerActor()
    props.args shouldBe 'empty

    val manager = TestActorRef[MediaManagerActor](props)
    manager.underlyingActor shouldBe a[MediaManagerActor]
    manager.underlyingActor shouldBe a[ChildActorFactory]
  }

  it should "create default helper objects" in {
    val manager = TestActorRef[MediaManagerActor](MediaManagerActor())
    manager.underlyingActor.directoryScanner shouldBe a[DirectoryScanner]
    manager.underlyingActor.idCalculator shouldBe a[MediumIDCalculator]
    manager.underlyingActor.mediumInfoParser shouldBe a[MediumInfoParser]
  }

  it should "provide information about currently available media" in {
    val helper = new MediaManagerTestHelper
    val manager = helper.scanMedia()

    manager ! MediaManagerActor.GetAvailableMedia
    helper checkMediaWithDescriptions expectMsgType[MediaManagerActor.AvailableMedia]
  }

  it should "stop temporary child actors when their answers are received" in {
    val helper = new MediaManagerTestHelper
    helper.scanMedia()

    val exitProbe = TestProbe()
    for {cls <- List[Class[_]](classOf[DirectoryScannerActor], classOf[MediumIDCalculatorActor],
      classOf[MediumInfoParserActor])
         probe <- helper.probesOfActorClass(cls)} {
      exitProbe watch probe.ref
      exitProbe.expectMsgType[Terminated]
    }
  }

  it should "include medium IDs for other files" in {
    val helper = new MediaManagerTestHelper
    val manager = helper.scanMedia()

    manager ! MediaManagerActor.GetAvailableMedia
    val media = expectMsgType[MediaManagerActor.AvailableMedia]
    media.media(helper.Drive1OtherIDData.mediumID) should be(MediumInfoParserActor
      .undefinedMediumInfo)
    media.media(helper.Drive3OtherIDData.mediumID) should be(MediumInfoParserActor
      .undefinedMediumInfo)
  }

  it should "include a medium ID for a combined list of other files" in {
    val helper = new MediaManagerTestHelper
    val manager = helper.scanMedia()

    manager ! MediaManagerActor.GetAvailableMedia
    val media = expectMsgType[MediaManagerActor.AvailableMedia]
    media.media(MediaManagerActor.MediumIDOtherFiles) should be(MediumInfoParserActor
      .undefinedMediumInfo)
  }

  /**
   * Prepares a test helper instance for a test which requires scanned media.
   * @param optMapping an optional reader actor mapping
   * @return the test helper
   */
  private def prepareHelperForScannedMedia(optMapping: Option[MediaReaderActorMapping] = None):
  MediaManagerTestHelper = {
    val helper = new MediaManagerTestHelper(optMapping = optMapping)
    val manager = helper.scanMedia()
    manager ! MediaManagerActor.GetAvailableMedia
    expectMsgType[MediaManagerActor.AvailableMedia]
    helper
  }

  it should "support queries for the files on a medium" in {
    val helper = prepareHelperForScannedMedia()

    helper.testManagerActor ! MediaManagerActor.GetMediumFiles(helper.Medium1IDData.mediumID)
    val msgFiles = expectMsgType[MediaManagerActor.MediumFiles]
    msgFiles.mediumID should be(helper.Medium1IDData.mediumID)
    msgFiles.existing shouldBe true
    helper.Medium1IDData.fileURIMapping.keySet.sameElements(msgFiles.uris) shouldBe true
  }

  it should "answer a query for the files of a non-existing medium" in {
    val helper = prepareHelperForScannedMedia()

    val request = MediaManagerActor.GetMediumFiles("non existing medium ID!")
    helper.testManagerActor ! request
    val msgFiles = expectMsgType[MediaManagerActor.MediumFiles]
    msgFiles.mediumID should be(request.mediumID)
    msgFiles.uris shouldBe 'empty
    msgFiles.existing shouldBe false
  }

  it should "answer a query for other files on a specific root path" in {
    val helper = prepareHelperForScannedMedia()
    val expURIs = helper.Drive3OtherFiles map (_.path.toString)

    helper.testManagerActor ! MediaManagerActor.GetMediumFiles(helper.Drive3OtherIDData.mediumID)
    val msgFiles = expectMsgType[MediaManagerActor.MediumFiles]
    msgFiles.uris.size should be(expURIs.size)
    msgFiles.uris.subsetOf(expURIs.toSet) shouldBe true
  }

  it should "answer a query for the global list of other files" in {
    val helper = prepareHelperForScannedMedia()
    val expURIs = (helper.Drive1OtherFiles ::: helper.Drive3OtherFiles) map (_.path.toString)

    helper.testManagerActor ! MediaManagerActor.GetMediumFiles(MediaManagerActor.MediumIDOtherFiles)
    val msgFiles = expectMsgType[MediaManagerActor.MediumFiles]
    msgFiles.uris.size should be(expURIs.size)
    msgFiles.uris.subsetOf(expURIs.toSet) shouldBe true
  }

  it should "create a valid ID3 data extractor" in {
    val helper = new MediaManagerTestHelper

    helper.testManagerActor.underlyingActor.id3Extractor shouldBe a[ID3HeaderExtractor]
  }

  /**
   * Checks whether a request for a non-existing audio source is handled
   * correctly.
   * @param sourceID the ID of the source to be requested
   */
  private def checkUnknownSourceIDRequest(sourceID: AudioSourceID): Unit = {
    val helper = prepareHelperForScannedMedia()

    helper.testManagerActor ! sourceID
    val response = expectMsgType[AudioSourceDownloadResponse]
    response.sourceID should be(sourceID)
    response.length should be(-1)
    val readerProbe = helper.probesOfType[MediaFileReaderActor].head
    val readRequest = FileReaderActor.ReadData(32)
    response.contentReader ! readRequest
    readerProbe.expectMsg(readRequest)
  }

  it should "return a download result for an unknown audio source ID" in {
    checkUnknownSourceIDRequest(AudioSourceID("unknown medium", "unknown URI"))
  }

  it should "return a download result for a source ID with an unknown URI" in {
    checkUnknownSourceIDRequest(AudioSourceID(MediaManagerActor.MediumIDOtherFiles, "unknown URI"))
  }

  /**
   * Creates the ID of an audio source which exists in the directory structures
   * scanned by the test actor.
   * @param helper the test helper
   * @return the audio source
   */
  private def createExistingAudioSourceID(helper: MediaManagerTestHelper): AudioSourceID = {
    val fileURI = helper.Medium1IDData.fileURIMapping.keys.head
    AudioSourceID(helper.Medium1IDData.mediumID, fileURI)
  }

  it should "return a correct download result" in {
    val helper = prepareHelperForScannedMedia()

    val sourceID = createExistingAudioSourceID(helper)
    val file = helper.Medium1IDData.fileURIMapping(sourceID.uri)
    helper.testManagerActor ! sourceID
    val response = expectMsgType[AudioSourceDownloadResponse]
    response.sourceID should be(sourceID)
    response.length should be(file.size)

    val readerProbe = helper.probesOfType[MediaFileReaderActor].head
    readerProbe.expectMsg(ChannelHandler.InitFile(file.path))
    response.contentReader should be(readerProbe.ref)
  }

  it should "send media information to clients when it becomes available" in {
    val helper = new MediaManagerTestHelper
    val probe = TestProbe()

    helper.testManagerActor ! MediaManagerActor.GetAvailableMedia
    helper.testManagerActor.tell(MediaManagerActor.GetAvailableMedia, probe.ref)
    helper.scanMedia()
    val msgMedia = expectMsgType[MediaManagerActor.AvailableMedia]
    helper.checkMediaWithDescriptions(msgMedia)
    probe.expectMsg(msgMedia)
  }

  it should "handle a scan operation that does not yield media" in {
    val helper = new MediaManagerTestHelper

    helper.testManagerActor ! MediaManagerActor.GetAvailableMedia
    helper.testManagerActor ! MediaManagerActor.ScanMedia(Nil)
    val msgMedia = expectMsgType[MediaManagerActor.AvailableMedia]
    msgMedia.media should have size 0
  }

  it should "support multiple scan operations" in {
    val helper = new MediaManagerTestHelper
    helper.testManagerActor ! MediaManagerActor.GetAvailableMedia
    helper.scanMedia()
    helper.checkMediaWithDescriptions(expectMsgType[MediaManagerActor.AvailableMedia])

    helper.testManagerActor ! MediaManagerActor.ScanMedia(Nil)
    helper.testManagerActor ! MediaManagerActor.GetAvailableMedia
    val msgMedia = expectMsgType[MediaManagerActor.AvailableMedia]
    msgMedia.media should have size 0
    helper.testManagerActor ! MediaManagerActor.GetMediumFiles("someMedium")
    expectMsgType[MediaManagerActor.MediumFiles].uris shouldBe 'empty
  }

  it should "ignore another scan request while a scan is in progress" in {
    val helper = new MediaManagerTestHelper
    val manager = helper.sendScanRequest()

    manager ! ScanMedia(List("UnsupportedTestPath"))
    helper.scanMedia()
    manager ! MediaManagerActor.GetAvailableMedia
    helper checkMediaWithDescriptions expectMsgType[MediaManagerActor.AvailableMedia]
  }

  it should "handle IO exceptions when scanning directories" in {
    val helper = new MediaManagerTestHelper(childActorFunc = { (ctx, props) =>
      props.actorClass() match {
        case MediaManagerActorSpec.ClsDirScanner =>
          Some(ctx.actorOf(props, "DirScannerActor"))
        case _ => None
      }
    })

    helper.testManagerActor ! MediaManagerActor.GetAvailableMedia
    helper.testManagerActor ! MediaManagerActor.ScanMedia(List("non existing directory!"))
    val mediaMsg = expectMsgType[MediaManagerActor.AvailableMedia]
    mediaMsg.media shouldBe 'empty
  }

  it should "handle IO operation exceptions sent from a file loader actor" in {
    val helper = new MediaManagerTestHelper(childActorFunc = { (ctx, props) =>
      if (props.actorClass() == classOf[FileLoaderActor]) {
        Some(ctx.actorOf(Props(new Actor {
          override def receive: Receive = {
            case FileLoaderActor.LoadFile(p) =>
              sender ! FileOperationActor.IOOperationError(p, new Exception("TestException"))
          }
        })))
      } else None
    })

    helper.scanMedia()
    helper.testManagerActor ! MediaManagerActor.GetAvailableMedia
    val mediaMsg = expectMsgType[MediaManagerActor.AvailableMedia]
    mediaMsg.media(helper.Medium1IDData.mediumID).name should be(MediumInfoParserActor
      .undefinedMediumInfo.name)
  }

  it should "create a default reader actor mapping" in {
    val testActor = TestActorRef[MediaManagerActor](MediaManagerActor())
    testActor.underlyingActor.readerActorMapping shouldBe a[MediaReaderActorMapping]
  }

  /**
   * Obtains the mapping for the last created reader actor. This method returns
   * a tuple with the test probes created for the media reader and its
   * underlying reader.
   * @param helper the test helper
   * @return a tuple with the test probes created for the reader actors
   */
  private def fetchReaderActorMapping(helper: MediaManagerTestHelper): (TestProbe, TestProbe) = {
    val procReader = helper.probesOfType[MediaFileReaderActor].head
    val actReader = helper.probesOfType[FileReaderActor].head
    (procReader, actReader)
  }

  it should "add newly created reader actors to the mapping" in {
    val mapping = mock[MediaReaderActorMapping]
    val helper = prepareHelperForScannedMedia(Some(mapping))
    helper.testManagerActor ! createExistingAudioSourceID(helper)

    expectMsgType[AudioSourceDownloadResponse]
    val (procReader, actReader) = fetchReaderActorMapping(helper)
    val captor = ArgumentCaptor forClass classOf[Long]
    verify(mapping).add(argEq((procReader.ref, actReader.ref)), captor.capture())
    val timestamp = captor.getValue
    Duration(System.currentTimeMillis() - timestamp, MILLISECONDS) should be <= 10.seconds
  }

  it should "stop the underlying reader actor when the processing reader is stopped" in {
    val helper = prepareHelperForScannedMedia()
    helper.testManagerActor ! createExistingAudioSourceID(helper)
    expectMsgType[AudioSourceDownloadResponse]
    val (procReader, actReader) = fetchReaderActorMapping(helper)

    val watcher = TestProbe()
    watcher watch actReader.ref
    system stop procReader.ref
    watcher.expectMsgType[Terminated].actor should be (actReader.ref)
  }

  it should "stop reader actors that timed out" in {
    val mapping = new MediaReaderActorMapping
    val procReader1, fileReader1, procReader2, fileReader2, watcher = TestProbe()
    val now = System.currentTimeMillis()
    mapping.add(procReader1.ref -> fileReader1.ref, now - 65 * 1000)
    mapping.add(procReader2.ref -> fileReader2.ref, now)
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
    mapping.add(procReader.ref -> fileReader.ref, 0L)
    val helper = new MediaManagerTestHelper(optMapping = Some(mapping))
    watcher watch procReader.ref

    helper.testManagerActor ! MediaManagerActor.ReaderActorAlive(procReader.ref)
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
    helper.testManagerActor ! MediaManagerActor.ReaderActorAlive(probe.ref)

    system stop helper.testManagerActor
    val invocation = RecordingSchedulerSupport.expectInvocation(helper.schedulerQueue)
    awaitCond(invocation.cancellable.isCancelled)
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
  private class MediaManagerTestHelper(optMapping: Option[MediaReaderActorMapping] = None,
    childActorFunc: (ActorContext, Props) => Option[ActorRef] = (ctx, p) => None) {
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
    private def pathList(dir: Path, count: Int): List[MediaFile] = {
      ((1 to count) map { i => MediaFile(path(dir, s"file$i.mp3"), 1000 + i * 10) }).toList
    }

    /**
     * Generates a ''MediumIDData'' object for a medium.
     * @param mediumNo the medium number
     * @param medPath the path to the medium
     * @param content the content of this medium
     * @return the corresponding ID data
     */
    private def idData(mediumNo: Int, medPath: Path, content: List[MediaFile]): MediumIDData =
      MediumIDData(mediumID(mediumNo), medPath.toString, pathMapping(content))

    /**
     * Returns the medium ID for the given medium.
     * @param mediumNo the medium number
     * @return the ID for this medium
     */
    private def mediumID(mediumNo: Int): String = s"Medium${mediumNo}_ID"

    /**
     * Generates a mapping from logic file URIs to physical paths.
     * @param content the list with media files
     * @return the path mapping
     */
    private def pathMapping(content: List[MediaFile]): Map[String, MediaFile] = {
      Map(content map (f => f.path.toString -> f): _*)
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
    private def settingsData(mediumRoot: Path, mediumNo: Int): MediumSettingsData =
      MediumSettingsData(name = s"Medium $mediumNo", description = s"Medium description $mediumNo",
        mediumURI = mediumRoot.toString, orderMode = "", orderParams = xml.NodeSeq.Empty)

    /**
     * Generates a request for calculating a medium ID.
     * @param path the medium path
     * @param content the medium content
     * @return the corresponding request
     */
    private def calcRequest(path: Path, content: Seq[MediaFile]): MediumIDCalculatorActor
    .CalculateMediumID =
      MediumIDCalculatorActor.CalculateMediumID(path, path.toString, content)

    /** Root of the first drive. */
    val Drive1Root = path("drive1")

    /** Other files on drive 1. */
    val Drive1OtherFiles = pathList(path(Drive1Root, "other"), 4)

    /** Root path of medium 1. */
    val Medium1Path = mediumPath(Drive1Root, 1)

    /** Description file for medium 1. */
    val Medium1Desc = mediumSettings(Medium1Path, 1)

    /** Content of the first medium. */
    val Medium1Content = pathList(path(Medium1Path, "data"), 5)

    /** ID data for medium 1. */
    val Medium1IDData = idData(1, Medium1Path, Medium1Content)

    /** Settings data for medium 1. */
    val Medium1SettingsData = settingsData(Medium1Path, 1)

    /** Binary content of the description file for medium 1. */
    val Medium1BinaryDesc = new Array[Byte](1)

    /** ID data for other files found on drive 1. */
    val Drive1OtherIDData = MediumIDData("Other1", "", pathMapping(pathList(Drive1Root, 8)))

    /** Root path of medium 2. */
    val Medium2Path = mediumPath(Drive1Root, 2)

    /** Description file for medium 2. */
    val Medium2Desc = mediumSettings(Medium2Path, 2)

    /** Content of medium 2. */
    val Medium2Content = pathList(path(Medium2Path, "audio"), 8)

    /** ID data for medium 2. */
    val Medium2IDData = idData(2, Medium2Path, Medium2Content)

    /** Settings data for medium 2. */
    val Medium2SettingsData = settingsData(Medium2Path, 2)

    /** Binary content of the description file for medium 2. */
    val Medium2BinaryDesc = new Array[Byte](2)

    /** Root of the second drive. */
    val Drive2Root = path("drive2")

    /** Root path of medium 3. */
    val Medium3Path = mediumPath(Drive2Root, 3)

    /** Description file for medium 3. */
    val Medium3Desc = mediumSettings(Medium3Path, 3)

    /** Content of medium 3. */
    val Medium3Content = pathList(path(Medium3Path, "music"), 16)

    /** ID data for medium 3. */
    val Medium3IDData = idData(3, Medium3Path, Medium3Content)

    /** Settings data for medium 3. */
    val Medium3SettingsData = settingsData(Medium3Path, 3)

    /** Binary content of the description file for medium 3. */
    val Medium3BinaryDesc = new Array[Byte](3)

    /** Root of the third drive. */
    val Drive3Root = path("3rdDrive")

    /** Drive 3 only has other files without a medium description. */
    val Drive3OtherFiles = pathList(path(Drive3Root, "myMusic"), 32)

    /** ID data for other files found on drive 3. */
    val Drive3OtherIDData = MediumIDData("Other-3", "", pathMapping(Drive3OtherFiles))

    /** The scan result for drive 1. */
    val Drive1 = MediaScanResult(Drive1Root, Map(Medium1Desc -> Medium1Content,
      Medium2Desc -> Medium2Content), Drive1OtherFiles)

    /** Scan result for drive 2. */
    val Drive2 = MediaScanResult(Drive2Root, Map(Medium3Desc -> Medium3Content), List.empty)

    /** Scan result for drive 3. */
    val Drive3 = MediaScanResult(Drive3Root, Map.empty, Drive3OtherFiles)

    /**
     * A map with messages that are expected by collaboration actors and
     * their corresponding responses.
     */
    private val ActorMessages = Map[Any, Any](DirectoryScannerActor.ScanPath(Drive1Root) -> Drive1,
      DirectoryScannerActor.ScanPath(Drive2Root) -> Drive2,
      DirectoryScannerActor.ScanPath(Drive3Root) -> Drive3,
      calcRequest(Medium1Path, Medium1Content) -> Medium1IDData,
      calcRequest(Medium2Path, Medium2Content) -> Medium2IDData,
      calcRequest(Medium3Path, Medium3Content) -> Medium3IDData,
      MediumIDCalculatorActor.CalculateMediumID(Drive1Root, "", Drive1OtherFiles) ->
        Drive1OtherIDData,
      MediumIDCalculatorActor.CalculateMediumID(Drive3Root, "", Drive3OtherFiles) ->
        Drive3OtherIDData,
      FileLoaderActor.LoadFile(Medium1Desc) -> FileLoaderActor.FileContent(Medium1Desc,
        Medium1BinaryDesc),
      FileLoaderActor.LoadFile(Medium2Desc) -> FileLoaderActor.FileContent(Medium2Desc,
        Medium2BinaryDesc),
      FileLoaderActor.LoadFile(Medium3Desc) -> FileLoaderActor.FileContent(Medium3Desc,
        Medium3BinaryDesc),
      MediumInfoParserActor.ParseMediumInfo(Medium1BinaryDesc, Medium1SettingsData.mediumURI) ->
        Medium1SettingsData,
      MediumInfoParserActor.ParseMediumInfo(Medium2BinaryDesc, Medium2SettingsData.mediumURI) ->
        Medium2SettingsData,
      MediumInfoParserActor.ParseMediumInfo(Medium3BinaryDesc, Medium3SettingsData.mediumURI) ->
        Medium3SettingsData)

    /**
     * A map for storing actors created by the test child actor factory. Each
     * time a child actor is requested, a ''TestProbe'' is created and stored
     * in this map for the corresponding actor class.
     */
    private val probes = createTestProbesMap()

    /** A queue for storing scheduler invocations. */
    val schedulerQueue = new LinkedBlockingQueue[RecordingSchedulerSupport.SchedulerInvocation]

    /** The actor used for tests. */
    lazy val testManagerActor = createTestActor()

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
      testManagerActor ! MediaManagerActor.ScanMedia(List(Drive1Root.toString, Drive2Root
        .toString, Drive3Root.toString))
      testManagerActor
    }

    /**
     * Checks whether the data object with available media contains all media
     * for which description files are provided.
     * @param avMedia the data object with available media
     * @return the same passed in data object
     */
    def checkMediaWithDescriptions(avMedia: MediaManagerActor.AvailableMedia): MediaManagerActor
    .AvailableMedia = {
      avMedia.media(mediumID(1)) should be(Medium1SettingsData)
      avMedia.media(mediumID(2)) should be(Medium2SettingsData)
      avMedia.media(mediumID(3)) should be(Medium3SettingsData)
      avMedia
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
    private def simulateCollaboratingActors(): Unit = {
      simulateCollaboratingActorsOfType[DirectoryScannerActor.ScanPath] (classOf[DirectoryScannerActor])
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
      for (i <- 1 to NumberOfMessages) {
        simulateCollaboratingActorsOfType[FileLoaderActor.LoadFile](classOf[FileLoaderActor])
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
      TestActorRef[MediaManagerActor](Props(new MediaManagerActor(mapping) with ChildActorFactory
        with RecordingSchedulerSupport {
        override def createChildActor(p: Props): ActorRef = {
          childActorFunc(context, p) getOrElse createProbeForChildActor(checkArgs(p)).ref
        }

        override val queue: BlockingQueue[SchedulerInvocation] = schedulerQueue
      }))
    }

    /**
     * Creates the map which stores the test probes used by this test helper
     * class. Some default values are set for typical actor classes. (This is
     * done to make it possible to inject test actors for the default probes.
     * In this case, the map still needs to contain empty lists.)
     * @return the initial map with test probes
     */
    private def createTestProbesMap(): collection.mutable.Map[Class[_], List[TestProbe]] =
      collection.mutable.Map(classOf[FileLoaderActor] -> Nil,
        MediaManagerActorSpec.ClsInfoParser -> Nil)
  }

}
