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

package de.oliver_heger.linedj.archive.media

import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.StateTestHelper
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.metadata.MetaDataManagerActor
import de.oliver_heger.linedj.archivecommon.download.{DownloadConfig, DownloadMonitoringActor, MediaFileDownloadActor}
import de.oliver_heger.linedj.archivecommon.parser.MediumInfoParser
import de.oliver_heger.linedj.extract.id3.processor.ID3v2ProcessingStage
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor
import de.oliver_heger.linedj.io.{CloseHandlerActor, CloseRequest, CloseSupport, FileData}
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.shared.archive.union.{MediaFileUriHandler, RemovedArchiveComponentProcessed}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.commons.configuration.PropertiesConfiguration
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

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
  private val TestMedium = MediumID("MyTestMedium",
    Some(RootPath.resolve("test.settings").toString), ArchiveName)

  /** A test file URI. */
  private val FileUri = "artist/album/song.mp3"

  /** The maximum size of medium description files. */
  private val InfoSizeLimit = 9876

  /** The chunk size for download operations. */
  private val DownloadChunkSize = 11111

  /** Test value for the buffer size property during scanning. */
  private val ScanBufSize = 47

  /** Test value for the parser timeout property. */
  private val ParserTimeout = Timeout(100.seconds)

  /** Test file data to be managed by the test actor. */
  private val TestFileData = createFileData()

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
    * Creates a map with test file data.
    *
    * @return the test file data
    */
  private def createFileData(): Map[MediumID, Map[String, FileData]] = {
    val path = RootPath resolve FileUri
    val uri = MediaFileUriHandler.generateMediaFileUri(RootPath, path)
    val fileMap = Map(uri -> FileData(FileUri, 20180419))
    Map(TestMedium -> fileMap)
  }

  /**
    * Returns the URI of the single file contained in the test file data.
    *
    * @return the URI of the test file
    */
  private def testFileUri: String = TestFileData(TestMedium).keys.head

  /**
    * Returns the test file from the test file data.
    *
    * @return data about the test file
    */
  private def testMediumFile: FileData = TestFileData(TestMedium)(testFileUri)

  /**
    * Creates a request for the test medium file which is part of the test
    * data.
    *
    * @param withMetaData flag whether meta data should be included
    * @return the request
    */
  private def createMediumFileRequest(withMetaData: Boolean): MediumFileRequest = {
    MediumFileRequest(MediaFileID(TestMedium, testFileUri), withMetaData)
  }
}

/**
  * Test class for ''MediaManagerActor''.
  */
class MediaManagerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("MediaManagerActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import MediaManagerActorSpec._

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
    when(config.rootPath).thenReturn(RootPath)
    when(config.downloadConfig).thenReturn(downloadConfig)
    config
  }

  "A MediaManagerActor" should "create a correct Props object" in {
    val config = createConfiguration()
    val unionActor = TestProbe()
    val props = MediaManagerActor(config, testActor, unionActor.ref)
    props.args should be(List(config, testActor, unionActor.ref))

    classOf[MediaManagerActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[CloseSupport].isAssignableFrom(props.actorClass()) shouldBe true
  }

  it should "use a default state update service" in {
    val manager = TestActorRef[MediaManagerActor](MediaManagerActor(createConfiguration(),
      testActor, TestProbe().ref))

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

  it should "handle a message to start a new scan operation" in {
    val state1 = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true)
    val scanMsg = MediaScannerActor.ScanPath(RootPath, state1.seqNo)
    val initMsg = ScanStateTransitionMessages(unionArchiveMessage = Some("union"),
      metaManagerMessage = Some("meta"))
    val helper = new MediaManagerTestHelper

    helper.stub(Option(scanMsg), state1) {
      _.triggerStartScan(RootPath)
    }
      .stub(initMsg, MediaScanStateUpdateServiceImpl.InitialState) {
        _.startScanMessages(ArchiveName)
      }
      .post(ScanAllMedia)
      .expectMediaScannerMessage(scanMsg)
      .expectUnionArchiveMessage(initMsg.unionArchiveMessage.get)
      .expectMetaDataMessage(initMsg.metaManagerMessage.get)
      .expectStateUpdate(MediaScanStateUpdateServiceImpl.InitialState)
      .expectStateUpdate(state1)
  }

  it should "handle a message to start a new scan operation if one is ongoing" in {
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(scanInProgress = true)
    val scanMsg: Option[MediaScannerActor.ScanPath] = None
    val helper = new MediaManagerTestHelper

    helper.stub(scanMsg, state) {
      _.triggerStartScan(RootPath)
    }
      .send(ScanAllMedia)
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
    probeAck.expectMsg(ScanSinkActor.Ack)
  }

  it should "handle a scan completed message" in {
    val completeMsg = MediaScannerActor.PathScanCompleted(
      MediaScannerActor.ScanPath(RootPath, 30))
    val state = MediaScanStateUpdateServiceImpl.InitialState.copy(fileData = TestFileData)
    val messages = ScanStateTransitionMessages(metaManagerMessage = Some("availableMedia"))
    val result = mock[ScanSinkActor.CombinedResults]
    val helper = new MediaManagerTestHelper

    helper.stub(messages, state) {
      _.handleScanComplete(completeMsg.request.seqNo, ArchiveName)
    }
      .stub(ScanStateTransitionMessages(), MediaScanStateUpdateServiceImpl.InitialState) {
        _.handleResultsReceived(result, testActor, ArchiveName)
      }
      .post(completeMsg)
      .expectStateUpdate(MediaScanStateUpdateServiceImpl.InitialState)
      .expectMetaDataMessage(messages.metaManagerMessage.get)
      .post(result)
      .expectStateUpdate(state)
  }

  it should "support queries for the files of a medium" in {
    val helper = new MediaManagerTestHelper

    helper.passTestData()
      .post(GetMediumFiles(TestMedium))
    val msgFiles = expectMsgType[MediumFiles]
    msgFiles.mediumID should be(TestMedium)
    msgFiles.uris should contain theSameElementsAs TestFileData(TestMedium).keys
    msgFiles.existing shouldBe true
  }

  it should "answer a query for the files of a non-existing medium" in {
    val mid = MediumID("nonExistingMedium", None)
    val helper = new MediaManagerTestHelper

    helper post GetMediumFiles(mid)
    val msgFiles = expectMsgType[MediumFiles]
    msgFiles.mediumID should be(mid)
    msgFiles.uris should have size 0
    msgFiles.existing shouldBe false
  }

  /**
    * Checks whether a request for a non-existing media file is handled
    * correctly.
    *
    * @param request the ID of the source to be requested
    */
  private def checkUnknownFileRequest(request: MediumFileRequest): Unit = {
    val helper = new MediaManagerTestHelper

    helper.passTestData()
      .post(request)
    val response = expectMsgType[MediumFileResponse]
    response.request should be(request)
    response.length should be(-1)
    response.contentReader shouldBe 'empty
  }

  it should "return a file response for an unknown medium ID" in {
    checkUnknownFileRequest(MediumFileRequest(MediaFileID(MediumID("unknown medium", None),
      "unknown URI"), withMetaData = false))
  }

  it should "return a file response for a request with an unknown URI" in {
    checkUnknownFileRequest(MediumFileRequest(MediaFileID(TestMedium,
      "unknown URI"), withMetaData = false))
  }

  it should "return a correct download result" in {
    val request = createMediumFileRequest(withMetaData = true)
    val helper = new MediaManagerTestHelper

    helper.passTestData()
      .post(request)
    val response = expectMsgType[MediumFileResponse]
    response.request should be(request)
    val fileData = testMediumFile
    response.length should be(fileData.size)
    val downloadProps = helper.nextDownloadChildCreation().props
    downloadProps.args should be(List(Paths get fileData.path, DownloadChunkSize,
      MediaFileDownloadActor.IdentityTransform))
  }

  it should "specify a correct transform function in a media file request" in {
    val request = createMediumFileRequest(withMetaData = false)
    val helper = new MediaManagerTestHelper

    helper.passTestData()
      .post(request)
    expectMsgType[MediumFileResponse]
    val downloadProps = helper.nextDownloadChildCreation().props
    val func = downloadProps.args(2).asInstanceOf[MediaFileDownloadActor.DownloadTransformFunc]
    func("mp3") shouldBe a[ID3v2ProcessingStage]
    func("MP3") shouldBe a[ID3v2ProcessingStage]
    func isDefinedAt "mp4" shouldBe false
  }

  it should "handle a file request for a file in global undefined medium" in {
    val fileURI = "ref://" + TestMedium.mediumURI + ":" + ArchiveName + ":" +
      MediaFileUriHandler.PrefixPath + testFileUri
    val helper = new MediaManagerTestHelper

    helper.passTestData()
      .post(MediumFileRequest(MediaFileID(TestMedium, fileURI), withMetaData = true))
    val response = expectMsgType[MediumFileResponse]
    val fileData = testMediumFile
    response.length should be(fileData.size)
    val downloadProps = helper.nextDownloadChildCreation().props
    downloadProps.args.head should be(Paths get fileData.path)
  }

  it should "inform the download manager about newly created download actors" in {
    val request = createMediumFileRequest(withMetaData = true)
    val helper = new MediaManagerTestHelper

    val creation = helper.passTestData()
      .post(request)
      .nextDownloadChildCreation()
    expectMsgType[MediumFileResponse]
    helper.expectDownloadMonitorMessage(DownloadMonitoringActor.DownloadOperationStarted(
      creation.probe.ref, testActor))
  }

  /**
    * A test helper class managing a test actor instance and its dependencies.
    */
  private class MediaManagerTestHelper
    extends StateTestHelper[MediaScanState, MediaScanStateUpdateService] {
    /** Mock for the state update service. */
    override val updateService = mock[MediaScanStateUpdateService]

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
      * Expects a state transition from the passed in state.
      *
      * @param state the expected (original) state
      * @return this test helper
      */
    def expectStateUpdate(state: MediaScanState): MediaManagerTestHelper = {
      nextUpdatedState().get should be(state)
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
      * @return this test helper
      */
    def passTestData(transitions: ScanStateTransitionMessages = ScanStateTransitionMessages()):
    MediaManagerTestHelper = {
      val state = MediaScanStateUpdateServiceImpl.InitialState.copy(fileData = TestFileData)
      val result = mock[ScanSinkActor.CombinedResults]
      stub(transitions, state) {
        _.handleResultsReceived(result, testActor, ArchiveName)
      }
      post(result)
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
          probeUnionMediaActor.ref, updateService)
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
