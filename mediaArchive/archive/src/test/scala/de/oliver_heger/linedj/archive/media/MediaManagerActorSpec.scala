/*
 * Copyright 2015-2022 The Developers Team.
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

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.metadata.MetaDataManagerActor
import de.oliver_heger.linedj.archivecommon.download.{DownloadConfig, DownloadMonitoringActor, MediaFileDownloadActor}
import de.oliver_heger.linedj.archivecommon.parser.MediumInfoParser
import de.oliver_heger.linedj.extract.id3.processor.ID3v2ProcessingStage
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor
import de.oliver_heger.linedj.io.{CloseHandlerActor, CloseRequest, CloseSupport, FileData}
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.shared.archive.metadata.GetMetaDataFileInfo
import de.oliver_heger.linedj.shared.archive.union.RemovedArchiveComponentProcessed
import de.oliver_heger.linedj.utils.ChildActorFactory
import de.oliver_heger.linedj.{FileTestHelper, ForwardTestActor, StateTestHelper}
import org.apache.commons.configuration.PropertiesConfiguration
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq => argEq}
import org.mockito.Mockito.{verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration._

object MediaManagerActorSpec {
  /** Class for the directory scanner child actor. */
  private val ClsDirScanner: Class[_ <: Actor] = MediaScannerActor("", Set.empty,
    Set.empty, 100, null, Timeout(1.minute)).actorClass()

  /** Class for the medium info parser child actor. */
  private val ClsInfoParser: Class[MediumInfoParserActor] = classOf[MediumInfoParserActor]

  /** Class for the download manager actor. */
  private val ClsDownloadManagerActor: Class[_ <: Actor] =
    DownloadMonitoringActor(DownloadConfig(new PropertiesConfiguration)).actorClass()

  /** Class for an actor managing a download operation. */
  private val ClsDownloadActor: Class[_ <: Actor] = classOf[MediaFileDownloadActor]

  /** Root path for a scan operation. */
  private val RootPath = Paths get "someRootPath"

  /** The interval for reader actor timeout checks. */
  private val ReaderCheckInterval = 5.minutes

  /** The set with excluded file extensions. */
  private val ExcludedExtensions = Set("TXT", "JPG")

  /** The set with included file extensions. */
  private val IncludedExtensions = Set("MP3", "WAV")

  /** A name for the archive. */
  private val ArchiveName = "MyTestArchive"

  /** A test medium ID. */
  private val TestMedium = MediumID("MyTestMedium", Some(RootPath.resolve("test.settings").toString))

  /** A test file URI. */
  private val FileUri = MediaFileUri("artist/album/song.mp3")

  /** The maximum size of medium description files. */
  private val InfoSizeLimit = 9876

  /** The chunk size for download operations. */
  private val DownloadChunkSize = 11111

  /** Test value for the buffer size property during scanning. */
  private val ScanBufSize = 47

  /** Test value for the parser timeout property. */
  private val ParserTimeout = Timeout(100.seconds)

  /** Test file data to be managed by the test actor. */
  private val TestFileData = Map(TestMedium -> Set(FileUri))

  /**
    * A data class storing information about a download actor created as child
    * of the test actor for a download operation.
    *
    * @param probe the probe representing the actor
    * @param props the props used for actor creations
    */
  private case class DownloadChildCreation(probe: TestProbe, props: Props)

  /**
    * Expects that no message was sent to the specified test probe.
    *
    * @param probe the probe in question
    */
  private def expectNoMessageReceived(probe: TestProbe): Unit = {
    val msg = new Object
    probe.ref ! msg
    probe.expectMsg(msg)
  }

  /**
    * Creates a request for a test medium file which is part of the test
    * data.
    *
    * @param uri          the URI of the test file
    * @param withMetaData flag whether meta data should be included
    * @return the request
    */
  private def createMediumFileRequest(uri: String, withMetaData: Boolean): MediumFileRequest =
    MediumFileRequest(MediaFileID(TestMedium, uri), withMetaData)

  /**
    * Appends the URI of another test file to the given test file data.
    *
    * @param fileData the original file data
    * @param uri      the URI of the new file
    * @return the resulting file data with the URI added
    */
  private def addUri(fileData: Map[MediumID, Set[MediaFileUri]], uri: String): Map[MediumID, Set[MediaFileUri]] =
    Map(TestMedium -> (fileData(TestMedium) + MediaFileUri(uri)))
}

/**
  * Test class for ''MediaManagerActor''.
  */
class MediaManagerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar with FileTestHelper {
  def this() = this(ActorSystem("MediaManagerActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import MediaManagerActorSpec._

  /**
    * Returns the root path of the test archive. This path is below the managed
    * test directory, so that test media files can be created.
    *
    * @return the root path of the test archive
    */
  private def archiveRootPath: Path = testDirectory resolve RootPath

  /**
    * Creates a mock configuration object.
    *
    * @return the mock configuration
    */
  private def createConfiguration(): MediaArchiveConfig = {
    val downloadConfig = mock[DownloadConfig]
    val config = mock[MediaArchiveConfig]
    when(downloadConfig.downloadTimeout).thenReturn(60.seconds)
    when(downloadConfig.downloadCheckInterval).thenReturn(ReaderCheckInterval)
    when(downloadConfig.downloadChunkSize).thenReturn(DownloadChunkSize)
    when(config.archiveName).thenReturn(ArchiveName)
    when(config.excludedFileExtensions).thenReturn(ExcludedExtensions)
    when(config.includedFileExtensions).thenReturn(IncludedExtensions)
    when(config.scanMediaBufferSize).thenReturn(ScanBufSize)
    when(config.infoParserTimeout).thenReturn(ParserTimeout)
    when(config.infoSizeLimit).thenReturn(InfoSizeLimit)
    when(config.rootPath).thenReturn(archiveRootPath)
    when(config.downloadConfig).thenReturn(downloadConfig)
    config
  }

  "A MediaManagerActor" should "create a correct Props object" in {
    val config = createConfiguration()
    val unionActor = TestProbe()
    val groupManager = TestProbe()
    val converter = new PathUriConverter(RootPath)
    val props = MediaManagerActor(config, testActor, unionActor.ref, groupManager.ref, converter)
    props.args should be(List(config, testActor, unionActor.ref, groupManager.ref, converter))

    classOf[MediaManagerActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[CloseSupport].isAssignableFrom(props.actorClass()) shouldBe true
  }

  it should "use a default state update service" in {
    val manager = TestActorRef[MediaManagerActor](MediaManagerActor(createConfiguration(),
      testActor, TestProbe().ref, TestProbe().ref, new PathUriConverter(RootPath)))

    manager.underlyingActor.scanStateUpdateService should be(MediaScanStateUpdateServiceImpl)
  }

  it should "handle a close request" in {
    val helper = new MediaManagerTestHelper

    helper.post(CloseRequest)
      .expectMediaScannerMessage(AbstractStreamProcessingActor.CancelStreams)
      .expectCloseRequestProcessed()
  }

  it should "handle a close complete message" in {
    val probeAck = TestProbe()
    val messages = ScanStateTransitionMessages(ack = Some(probeAck.ref))
    val helper = new MediaManagerTestHelper

    helper.stub(messages, MediaScanStateUpdateServiceImpl.InitialState) {
      _.handleScanCanceled()
    }
      .post(CloseHandlerActor.CloseComplete)
      .expectStateUpdate(MediaScanStateUpdateServiceImpl.InitialState)
      .expectCloseCompleteProcessed()
    probeAck.expectMsg(ScanSinkActor.Ack)
  }

  it should "forward a scan request to the group manager" in {
    val helper = new MediaManagerTestHelper

    helper.post(ScanAllMedia)
      .expectGroupManagerMessage(ScanAllMedia)
  }

  it should "handle a message to start a new scan operation" in {
    val state1 = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(testActor))
    val scanMsg = MediaScannerActor.ScanPath(archiveRootPath, state1.seqNo)
    val initMsg = ScanStateTransitionMessages(unionArchiveMessage = Some("union"),
      metaManagerMessage = Some("meta"))
    val helper = new MediaManagerTestHelper

    helper.stub(Option(scanMsg), state1) {
      _.triggerStartScan(archiveRootPath, testActor)
    }
      .stub(initMsg, MediaScanStateUpdateServiceImpl.InitialState) {
        _.startScanMessages(ArchiveName)
      }
      .post(StartMediaScan)
      .expectMediaScannerMessage(scanMsg)
      .expectUnionArchiveMessage(initMsg.unionArchiveMessage.get)
      .expectMetaDataMessage(initMsg.metaManagerMessage.get)
      .expectStateUpdate(MediaScanStateUpdateServiceImpl.InitialState)
      .expectStateUpdate(state1)
  }

  it should "handle a message to start a new scan operation if one is ongoing" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanClient = Some(testActor))
    val scanMsg: Option[MediaScannerActor.ScanPath] = None
    val helper = new MediaManagerTestHelper

    helper.stub(scanMsg, state) {
      _.triggerStartScan(archiveRootPath, testActor)
    }
      .post(StartMediaScan)
      .expectStateUpdate(MediaScanStateUpdateServiceImpl.InitialState)
      .expectNoScannerMessage()
      .expectNoUnionArchiveMessage()
      .expectNoMetaDataMessage()
  }

  it should "handle a removed confirmation from the union archive" in {
    val probeAck = TestProbe()
    val messages = ScanStateTransitionMessages(metaManagerMessage = Some("message"),
      ack = Some(probeAck.ref))
    val helper = new MediaManagerTestHelper

    helper.stub(messages, MediaScanStateUpdateServiceImpl.InitialState) {
      _.handleRemovedFromUnionArchive(ArchiveName)
    }
      .send(RemovedArchiveComponentProcessed(ArchiveName))
      .expectMetaDataMessage(messages.metaManagerMessage.get)
      .expectNoUnionArchiveMessage()
    probeAck.expectMsg(ScanSinkActor.Ack)
  }

  it should "ignore a removed confirmation for another archive component" in {
    val helper = new MediaManagerTestHelper

    helper.send(RemovedArchiveComponentProcessed("some other ID"))
      .expectNoStateUpdate()
  }

  it should "handle an ACK from the meta data manager" in {
    val probeAck = TestProbe()
    val messages = ScanStateTransitionMessages(unionArchiveMessage = Some("message"),
      ack = Some(probeAck.ref))
    val helper = new MediaManagerTestHelper

    helper.stub(messages, MediaScanStateUpdateServiceImpl.InitialState) {
      _.handleAckFromMetaManager(ArchiveName)
    }
      .postAckFromMetaManager()
      .expectUnionArchiveMessage(messages.unionArchiveMessage.get)
      .expectNoMetaDataMessage()
    probeAck.expectMsg(ScanSinkActor.Ack)
  }

  it should "ignore an ACK message from another source" in {
    val helper = new MediaManagerTestHelper

    helper.send(MetaDataManagerActor.ScanResultProcessed)
      .expectNoStateUpdate()
  }

  it should "handle an incoming result object" in {
    val probeAck = TestProbe()
    val messages = ScanStateTransitionMessages(unionArchiveMessage = Some("message"),
      ack = Some(probeAck.ref), metaManagerMessage = Some("otherMessage"))
    val helper = new MediaManagerTestHelper

    helper.passTestData(messages)
      .expectUnionArchiveMessage(messages.unionArchiveMessage.get)
      .expectMetaDataMessage(messages.metaManagerMessage.get)
      .verifyResultsReceivedUriFunc()
    probeAck.expectMsg(ScanSinkActor.Ack)
  }

  it should "handle a scan completed message" in {
    val completeMsg = MediaScannerActor.PathScanCompleted(
      MediaScannerActor.ScanPath(RootPath, 30))
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(fileData = TestFileData)
    val helper = new MediaManagerTestHelper
    val messages = ScanStateTransitionMessages(metaManagerMessage = Some("availableMedia"))
    val result = mock[ScanSinkActor.CombinedResults]

    helper.stub(messages, state) {
      _.handleScanComplete(completeMsg.request.seqNo, ArchiveName)
    }
      .stub(ScanStateTransitionMessages(), MediaScanStateUpdateServiceImpl.InitialState) {
        _.handleResultsReceived(argEq(result), argEq(testActor), argEq(ArchiveName))(any())
      }
      .post(completeMsg)
      .expectStateUpdate(MediaScanStateUpdateServiceImpl.InitialState)
      .expectMetaDataMessage(messages.metaManagerMessage.get)
      .post(result)
      .expectStateUpdate(state)
      .verifyResultsReceivedUriFunc()
  }

  it should "support queries for the files of a medium" in {
    val actualMedium = TestMedium.copy(archiveComponentID = ArchiveName)
    val expectedIDs = TestFileData(TestMedium) map { uri => MediaFileID(actualMedium, uri.uri) }
    val helper = new MediaManagerTestHelper

    helper.passTestData()
      .post(GetMediumFiles(TestMedium))
    val msgFiles = expectMsgType[MediumFiles]
    msgFiles.mediumID should be(TestMedium)
    msgFiles.fileIDs should contain theSameElementsAs expectedIDs
    msgFiles.existing shouldBe true
  }

  it should "support queries for the files of a medium if the archive component ID is different" in {
    val requestedMedium = TestMedium.copy(archiveComponentID = "other")
    val actualMedium = TestMedium.copy(archiveComponentID = ArchiveName)
    val expectedIDs = TestFileData(TestMedium) map { uri => MediaFileID(actualMedium, uri.uri) }
    val helper = new MediaManagerTestHelper

    helper.passTestData()
      .post(GetMediumFiles(requestedMedium))
    val msgFiles = expectMsgType[MediumFiles]
    msgFiles.mediumID should be(requestedMedium)
    msgFiles.fileIDs should contain theSameElementsAs expectedIDs
    msgFiles.existing shouldBe true
  }

  it should "answer a query for the files of a non-existing medium" in {
    val mid = MediumID("nonExistingMedium", None)
    val helper = new MediaManagerTestHelper

    helper post GetMediumFiles(mid)
    val msgFiles = expectMsgType[MediumFiles]
    msgFiles.mediumID should be(mid)
    msgFiles.fileIDs shouldBe empty
    msgFiles.existing shouldBe false
  }

  it should "return a file response for an unknown medium ID" in {
    val helper = new MediaManagerTestHelper

    helper.checkUnknownFileRequest(MediumFileRequest(MediaFileID(MediumID("unknown medium", None),
      "unknown URI"), withMetaData = false))
  }

  it should "return a file response for a request with an unknown URI" in {
    val helper = new MediaManagerTestHelper
    val (uri, _) = helper.createTestMediaFile()

    helper.checkUnknownFileRequest(MediumFileRequest(MediaFileID(TestMedium, uri), withMetaData = false))
  }

  it should "return a file response for a non-existing path" in {
    val helper = new MediaManagerTestHelper
    val (uri, file) = helper.createTestMediaFile()
    val testData = addUri(TestFileData, uri)
    Files delete file.path

    helper.checkUnknownFileRequest(MediumFileRequest(MediaFileID(TestMedium, uri), withMetaData = false), testData)
  }

  it should "return a correct download result" in {
    val helper = new MediaManagerTestHelper
    val (uri, fileData) = helper.createTestMediaFile()
    val request = createMediumFileRequest(uri, withMetaData = true)

    helper.passTestData(data = addUri(TestFileData, uri))
      .post(request)
    val response = expectMsgType[MediumFileResponse]
    response.request should be(request)
    response.length should be(fileData.size)
    val downloadProps = helper.nextDownloadChildCreation().props
    downloadProps.args should be(List(fileData.path, DownloadChunkSize, MediaFileDownloadActor.IdentityTransform))
  }

  it should "specify a correct transform function in a media file request" in {
    val helper = new MediaManagerTestHelper
    val (uri, _) = helper.createTestMediaFile()
    val request = createMediumFileRequest(uri, withMetaData = false)

    helper.passTestData(data = addUri(TestFileData, uri))
      .post(request)
    expectMsgType[MediumFileResponse]
    val downloadProps = helper.nextDownloadChildCreation().props
    val func = downloadProps.args(2).asInstanceOf[MediaFileDownloadActor.DownloadTransformFunc]
    func("mp3") shouldBe a[ID3v2ProcessingStage]
    func("MP3") shouldBe a[ID3v2ProcessingStage]
    func isDefinedAt "mp4" shouldBe false
  }

  it should "return a correct download result even if a medium from another archive component is requested" in {
    val helper = new MediaManagerTestHelper
    val (uri, fileData) = helper.createTestMediaFile()
    val requestedMedium = TestMedium.copy(archiveComponentID = "fromAnotherArchiveComponent")
    val requestedFileID = MediaFileID(requestedMedium, uri)
    val request = MediumFileRequest(requestedFileID, withMetaData = true)

    helper.passTestData(data = addUri(TestFileData, uri))
      .post(request)
    val response = expectMsgType[MediumFileResponse]
    response.request should be(request)
    response.length should be(fileData.size)
    val downloadProps = helper.nextDownloadChildCreation().props
    downloadProps.args should be(List(fileData.path, DownloadChunkSize, MediaFileDownloadActor.IdentityTransform))
  }

  it should "inform the download manager about newly created download actors" in {
    val helper = new MediaManagerTestHelper
    val (uri, _) = helper.createTestMediaFile()
    val request = createMediumFileRequest(uri, withMetaData = true)

    val creation = helper.passTestData(data = addUri(TestFileData, uri))
      .post(request)
      .nextDownloadChildCreation()
    expectMsgType[MediumFileResponse]
    helper.expectDownloadMonitorMessage(DownloadMonitoringActor.DownloadOperationStarted(
      creation.probe.ref, testActor))
  }

  it should "forward a request for meta file info to the meta data manager" in {
    val metaDataManager = ForwardTestActor()
    val manager = system.actorOf(MediaManagerActor(createConfiguration(), metaDataManager,
      TestProbe().ref, TestProbe().ref, new PathUriConverter(RootPath)))

    manager ! GetMetaDataFileInfo
    expectMsg(ForwardTestActor.ForwardedMessage(GetMetaDataFileInfo))
  }

  /**
    * A test helper class managing a test actor instance and its dependencies.
    */
  private class MediaManagerTestHelper
    extends StateTestHelper[MediaScanState, MediaScanStateUpdateService] {
    /** Mock for the state update service. */
    override val updateService: MediaScanStateUpdateService = mock[MediaScanStateUpdateService]

    /** Test probe for the medium info parser actor. */
    private val probeInfoParser = TestProbe()

    /** Test probe for the media scanner actor. */
    private val probeMediaScanner = TestProbe()

    /** Test probe for the download manager actor. */
    private val probeDownloadManager = TestProbe()

    /** Test probe for the meta data manager actor. */
    private val probeMetaDataManager = TestProbe()

    /** Test probe for the union media manager actor. */
    private val probeUnionMediaActor = TestProbe()

    /** Test probe for the group manager actor. */
    private val probeGroupManager = TestProbe()

    /** Counter for close request handling. */
    private val closeRequestCount = new AtomicInteger

    /** Counter for handling of completed close requests. */
    private val closeCompleteCount = new AtomicInteger

    /** The mock for the configuration passed to the actor. */
    private val actorConfig = createConfiguration()

    /**
      * A queue which stores the state objects passed to the state monad
      * returned by the update service.
      */
    private val stateQueue = new LinkedBlockingQueue[MediaScanState]

    /**
      * A queue that stores information about child actors created for
      * download operations.
      */
    private val downloadActorsQueue = new LinkedBlockingQueue[DownloadChildCreation]

    /** The converter for Paths and URIs used by the test actor. */
    private val converter = new PathUriConverter(archiveRootPath)

    /** The actor used for tests. */
    private val testManagerActor: TestActorRef[MediaManagerActor] = createTestActor()

    /**
      * Posts the specified message to the test actor.
      *
      * @param msg the message
      * @return this test helper
      */
    def post(msg: Any): MediaManagerTestHelper = {
      testManagerActor ! msg
      this
    }

    /**
      * Sends the specified message to the test actor by passing it directly to
      * its ''receive'' method.
      *
      * @param msg the message
      * @return this test helper
      */
    def send(msg: Any): MediaManagerTestHelper = {
      testManagerActor receive msg
      this
    }

    /**
      * Simulates an ACK response from the meta data manager.
      *
      * @return this test helper
      */
    def postAckFromMetaManager(): MediaManagerTestHelper = {
      testManagerActor.tell(MetaDataManagerActor.ScanResultProcessed, probeMetaDataManager.ref)
      this
    }

    /**
      * Expects that the specified message has been sent to the media scanner
      * actor.
      *
      * @param msg the expected message
      * @return this test helper
      */
    def expectMediaScannerMessage(msg: Any): MediaManagerTestHelper =
      expectProbeMessage(probeMediaScanner, msg)

    /**
      * Checks that no message was sent to the media scanner actor.
      *
      * @return this test helper
      */
    def expectNoScannerMessage(): MediaManagerTestHelper =
      expectNoProbeMessage(probeMediaScanner)

    /**
      * Expects that the specified message has been sent to the union archive
      * actor.
      *
      * @param msg the expected message
      * @return this test helper
      */
    def expectUnionArchiveMessage(msg: Any): MediaManagerTestHelper =
      expectProbeMessage(probeUnionMediaActor, msg)

    /**
      * Checks that no message was sent to the union archive.
      *
      * @return this test helper
      */
    def expectNoUnionArchiveMessage(): MediaManagerTestHelper =
      expectNoProbeMessage(probeUnionMediaActor)

    /**
      * Expects that the specified message has been sent to the meta data
      * manager actor.
      *
      * @param msg the expected message
      * @return this test helper
      */
    def expectMetaDataMessage(msg: Any): MediaManagerTestHelper =
      expectProbeMessage(probeMetaDataManager, msg)

    /**
      * Checks that no message was sent to the meta data manager actor.
      *
      * @return this test helper
      */
    def expectNoMetaDataMessage(): MediaManagerTestHelper =
      expectNoProbeMessage(probeMetaDataManager)

    /**
      * Expects that the specified message has been sent to the download
      * monitoring actor.
      *
      * @param msg the expected message
      * @return this test helper
      */
    def expectDownloadMonitorMessage(msg: Any): MediaManagerTestHelper =
      expectProbeMessage(probeDownloadManager, msg)

    /**
      * Expects that the specified message has been sent to the group
      * manager actor.
      *
      * @param msg the expected message
      * @return this test helper
      */
    def expectGroupManagerMessage(msg: Any): MediaManagerTestHelper =
      expectProbeMessage(probeGroupManager, msg)

    /**
      * Expects that a close request has been handled.
      *
      * @return this test helper
      */
    def expectCloseRequestProcessed(): MediaManagerTestHelper = {
      awaitCond(closeRequestCount.get() == 1)
      this
    }

    /**
      * Expects that a close completed notification has been handled.
      *
      * @return this test helper
      */
    def expectCloseCompleteProcessed(): MediaManagerTestHelper = {
      awaitCond(closeCompleteCount.get() == 1)
      this
    }

    /**
      * Checks that no state update was performed. This can be used together
      * with a (synchronous) ''send'' call to verify that a message does not
      * cause an interaction with the state update service.
      *
      * @return this test helper
      */
    def expectNoStateUpdate(): MediaManagerTestHelper = {
      stateQueue should have size 0
      this
    }

    /**
      * Passes a message to the test actor and prepares the mock update
      * service to install the test file data in the actor's state. This can be
      * used to test access to the data managed by the actor.
      *
      * @param transitions optional transition messages to be generated
      * @param data        the test data to be passed
      * @return this test helper
      */
    def passTestData(transitions: ScanStateTransitionMessages = ScanStateTransitionMessages(),
                     data: Map[MediumID, Set[MediaFileUri]] = TestFileData): MediaManagerTestHelper = {
      val state = MediaScanStateUpdateServiceImpl.InitialState.copy(fileData = data)
      val result = mock[ScanSinkActor.CombinedResults]
      stub(transitions, state) {
        _.handleResultsReceived(argEq(result), argEq(testActor), argEq(ArchiveName))(any())
      }
      post(result)
    }

    /**
      * Verifies the URI function that was passed to the update service when
      * handling new results. It is tested whether this function yields the
      * same results as the ''PathUriConverter''.
      *
      * @return this test helper
      */
    def verifyResultsReceivedUriFunc(): MediaManagerTestHelper = {
      val capture = ArgumentCaptor.forClass(classOf[Path => MediaFileUri])
      verify(updateService).handleResultsReceived(any(), any(), any())(capture.capture())
      val uriFunc = capture.getValue.asInstanceOf[Path => MediaFileUri]
      val testPath = RootPath.resolve("testPath")
      uriFunc(testPath) should be(converter.pathToUri(testPath))
      this
    }

    /**
      * Returns information about the next child actor that has been created
      * for a download operation.
      *
      * @return information about a child download actor
      */
    def nextDownloadChildCreation(): DownloadChildCreation = {
      val data = downloadActorsQueue.poll(3, TimeUnit.SECONDS)
      data should not be null
      data
    }

    /**
      * Creates a media file in the test directory and returns its URI and
      * ''FileData'' representation.
      *
      * @return a tuple with the URI and the ''FileData'' of the test file
      */
    def createTestMediaFile(): (String, FileData) = {
      val path = archiveRootPath.resolve("some-medium")
        .resolve("someArtist")
        .resolve("someAlbum")
        .resolve("someGreatHit.mp3")
      val mediaFile = writeFileContent(path, FileTestHelper.TestData)
      (converter.pathToUri(mediaFile).uri, FileData(mediaFile, FileTestHelper.TestData.length))
    }

    /**
      * Checks whether a request for a non-existing media file is handled
      * correctly.
      *
      * @param request the ID of the source to be requested
      * @param data    the test file data to be used
      * @return this test helper
      */
    def checkUnknownFileRequest(request: MediumFileRequest, data: Map[MediumID, Set[MediaFileUri]] = TestFileData):
    MediaManagerTestHelper = {
      passTestData(data = data)
      post(request)
      val response = expectMsgType[MediumFileResponse]
      response.request should be(request)
      response.length should be(-1)
      response.contentReader shouldBe empty
      this
    }

    /**
      * Helper method to check whether the specified message was sent to the
      * given test probe.
      *
      * @param probe the probe
      * @param msg   the expected message
      * @return this test helper
      */
    private def expectProbeMessage(probe: TestProbe, msg: Any): MediaManagerTestHelper = {
      probe expectMsg msg
      this
    }

    /**
      * Helper method to check that the specified test prob did not receive a
      * message.
      *
      * @param probe the probe
      * @return this test helper
      */
    private def expectNoProbeMessage(probe: TestProbe): MediaManagerTestHelper = {
      expectNoMessageReceived(probe)
      this
    }

    /**
      * Creates a test actor instance as a test reference.
      *
      * @return the test reference
      */
    private def createTestActor(): TestActorRef[MediaManagerActor] = {
      TestActorRef[MediaManagerActor](Props(
        new MediaManagerActor(actorConfig, probeMetaDataManager.ref,
          probeUnionMediaActor.ref, probeGroupManager.ref, updateService, converter)
          with ChildActorFactory with CloseSupport {
          override def createChildActor(p: Props): ActorRef = {
            p.actorClass() match {
              case ClsDirScanner =>
                p.args should be(List(ArchiveName, ExcludedExtensions, IncludedExtensions,
                  ScanBufSize, probeInfoParser.ref, ParserTimeout))
                probeMediaScanner.ref

              case ClsInfoParser =>
                p.args.head shouldBe a[MediumInfoParser]
                p.args(1) should be(InfoSizeLimit)
                probeInfoParser.ref

              case ClsDownloadManagerActor =>
                p.args should be(List(actorConfig.downloadConfig))
                probeDownloadManager.ref

              case ClsDownloadActor =>
                val probe = TestProbe()
                downloadActorsQueue offer DownloadChildCreation(probe, p)
                probe.ref
            }
          }

          /**
            * Checks parameters and records this invocation.
            */
          override def onCloseRequest(subject: ActorRef, deps: => Iterable[ActorRef], target:
          ActorRef, factory: ChildActorFactory, conditionState: => Boolean): Boolean = {
            subject should be(testManagerActor)
            deps should contain only probeMetaDataManager.ref
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
  }

}
