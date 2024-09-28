/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp

import com.github.cloudfiles.core.http.HttpRequestSender.FailedResponseException
import de.oliver_heger.linedj.ForwardTestActor.ForwardedMessage
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.archivehttp.impl.*
import de.oliver_heger.linedj.archivehttp.impl.download.HttpDownloadManagementActor
import de.oliver_heger.linedj.archivehttp.io.MediaDownloader
import de.oliver_heger.linedj.archivehttp.temp.TempPathGenerator
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor.CancelStreams
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.shared.archive.media.*
import de.oliver_heger.linedj.shared.archive.metadata.{GetMetaDataFileInfo, MetaDataFileInfo}
import de.oliver_heger.linedj.shared.archive.union.{UpdateOperationCompleted, UpdateOperationStarts}
import de.oliver_heger.linedj.utils.ChildActorFactory
import de.oliver_heger.linedj.{ForwardTestActor, StateTestHelper}
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.pattern.{AskTimeoutException, ask}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import org.apache.pekko.util.{ByteString, Timeout}
import org.mockito.ArgumentMatchers.{any, eq as argEq}
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import java.nio.file.Paths
import java.util.concurrent.LinkedBlockingQueue
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

object HttpArchiveManagementActorSpec:

  /** The value of the propagation buffer size config property. */
  private val PropBufSize = 4

  /**
    * The template for the test archive configuration. (Some properties are
    * modified by specific tests.
    */
  private val ArchiveConfig =
    HttpArchiveConfig(archiveBaseUri = "https://some.archive.org/data/music",
      archiveName = "test", processorCount = 1, processorTimeout = Timeout(1.minute), propagationBufSize = PropBufSize,
      maxContentSize = 1024, downloadBufferSize = 1000, downloadMaxInactivity = 10.seconds,
      downloadReadChunkSize = 8192, timeoutReadSize = 111, downloadConfig = null, downloader = null,
      contentPath = Uri.Path("archiveContent.json"), mediaPath = Uri.Path("media"), metadataPath = Uri.Path("meta"))

  /** Constant for an archive name. */
  private val ArchiveName = ArchiveConfig.archiveName

  /** Constant for a sequence number. */
  private val SeqNo = 20180621

  /** Class for the content processor actor. */
  private val ClsContentProcessor = classOf[HttpArchiveContentProcessorActor]

  /** Class for the medium info processor actor. */
  private val ClsMediumInfoProcessor = classOf[MediumInfoResponseProcessingActor]

  /** Class for the metadata processor actor. */
  private val ClsMetadataProcessor = classOf[MetaDataResponseProcessingActor]

  /** Class for the content propagation actor. */
  private val ClsContentPropagationActor = classOf[ContentPropagationActor]

  /** Class for the download management actor. */
  private val ClsDownloadManagementActor =
    HttpDownloadManagementActor(null, null, null, null).actorClass()

  /** A state indicating that a scan operation is in progress. */
  private val ProgressState = ContentProcessingUpdateServiceImpl.InitialState
    .copy(scanInProgress = true)

  /**
    * A class for storing information about child actors created by the
    * management actor.
    *
    * @param props the creation Props of the child
    * @param child the child actor that was actually created
    */
  private case class ChildActorCreation(props: Props, child: ActorRef)

  /**
    * Checks that no further messages have been sent to the specified test
    * probe.
    *
    * @param probe the test probe
    */
  private def expectNoMoreMsg(probe: TestProbe): Unit =
    val msg = new Object
    probe.ref ! msg
    probe.expectMsg(msg)

  /**
    * Creates a list with test medium descriptions.
    *
    * @return the list with test descriptions
    */
  private def createMediumDescriptions(): List[HttpMediumDesc] =
    (1 to 4).map(i => HttpMediumDesc("descPath" + i, "metaDataPath" + i)).toList

  /**
    * Returns a JSON representation for a medium description.
    *
    * @param md the description
    * @return the JSON representation
    */
  private def createMediumDescriptionJson(md: HttpMediumDesc): String =
    s"""{ "mediumDescriptionPath": "${md.mediumDescriptionPath}",
       |"metaDataPath": "${md.metaDataPath}"}
   """.stripMargin

  /**
    * Generates a JSON representation of the test medium descriptions.
    *
    * @return the JSON description
    */
  private def createMediumDescriptionsJson(): String =
    createMediumDescriptions().map(md => createMediumDescriptionJson(md))
      .mkString("[", ", ", "]")

  /**
    * Creates a response data source for a successful request to the content
    * document of the test archive.
    *
    * @return the data source with the content document
    */
  private def createSuccessResponse(): Source[ByteString, Any] =
    val data = ByteString(createMediumDescriptionsJson()).grouped(128)
    Source(data.toList)


/**
  * Test class for ''HttpArchiveManagementActor''.
  */
class HttpArchiveManagementActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar:

  import HttpArchiveManagementActorSpec.*

  def this() = this(ActorSystem("HttpArchiveManagementActorSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  "A HttpArchiveManagementActor" should "create correct Props" in:
    val mediaManager = TestProbe()
    val metaManager = TestProbe()
    val monitoringActor = TestProbe()
    val removeActor = TestProbe()
    val pathGenerator = new TempPathGenerator(Paths get "temp")
    val props = HttpArchiveManagementActor(ArchiveConfig, pathGenerator, mediaManager.ref,
      metaManager.ref, monitoringActor.ref, removeActor.ref)
    classOf[HttpArchiveManagementActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(ContentProcessingUpdateServiceImpl, ArchiveConfig, pathGenerator,
      mediaManager.ref, metaManager.ref, monitoringActor.ref, removeActor.ref))

  /**
    * Checks a processing operation on a test archive.
    *
    * @param helper the prepared helper
    */
  private def checkProcessing(helper: HttpArchiveManagementActorTestHelper): Unit =
    val request = helper.stub((), ProgressState)(_.processingDone())
      .triggerScan().expectProcessingRequest()
    request.archiveConfig should be(helper.config)
    request.settingsProcessorActor should be(helper.probeMediumInfoProcessor.ref)
    request.metadataProcessorActor should be(helper.probeMetadataProcessor.ref)

    val futureDescriptions = request.mediaSource.runFold(
      List.empty[HttpMediumDesc])((lst, md) => md :: lst)
    val descriptions = Await.result(futureDescriptions, 5.seconds)
    val expDescriptions = createMediumDescriptions()
    descriptions should contain theSameElementsAs expDescriptions

    val result = mock[MediumProcessingResult]
    helper.stub(ProcessingStateTransitionData(None, None), mock[ContentProcessingState]) { svc =>
      svc.handleResultAvailable(argEq(result), any(), argEq(PropBufSize))
    }
    val srcResults = Source.single(result)
    srcResults.runWith(request.sink)
    helper.expectStateUpdate(ContentProcessingUpdateServiceImpl.InitialState)
      .expectStateUpdate(ProgressState)
    verify(helper.updateService).handleResultAvailable(argEq(result), any(), argEq(PropBufSize))

  it should "pass a process request to the content processor actor" in:
    checkProcessing(new HttpArchiveManagementActorTestHelper)

  it should "notify the union archive about a started scan operation" in:
    val helper = new HttpArchiveManagementActorTestHelper

    helper.stub((), ProgressState)(_.processingDone())
      .triggerScan()
    helper.probeUnionMetadataManager.expectMsg(UpdateOperationStarts(None))

  it should "notify the union archive about a completed scan operation" in:
    val helper = new HttpArchiveManagementActorTestHelper

    helper.stub((), ProgressState)(_.processingDone())
      .post(HttpArchiveProcessingComplete(HttpArchiveStateConnected))
    helper.probeUnionMetadataManager.expectMsg(UpdateOperationCompleted(None))

  it should "not send a process request for a response error" in:
    val helper = new HttpArchiveManagementActorTestHelper

    helper.stub((), ProgressState)(_.processingDone())
      .initFailedArchiveResponse(new Exception)
      .triggerScan(initSuccessResponse = false)
      .expectNoProcessingRequest()

  it should "recover from an error, so that a new request can be processed" in:
    val helper = new HttpArchiveManagementActorTestHelper

    helper.stub((), ProgressState)(_.processingDone())
      .initFailedArchiveResponse(new Exception)
      .triggerScan() // initializes a success response
      .triggerScan(stubProcStarts = false, initSuccessResponse = false)
      .expectProcessingRequest()

  it should "ignore a process request if a scan is ongoing" in:
    val helper = new HttpArchiveManagementActorTestHelper

    helper.stub(false, ProgressState)(_.processingStarts())
      .triggerScan(stubProcStarts = false)
      .expectNoProcessingRequest()
    expectNoMoreMsg(helper.probeUnionMetadataManager)

  it should "answer an archive processing init message" in:
    val helper = new HttpArchiveManagementActorTestHelper

    helper.post(HttpArchiveProcessingInit)
    expectMsg(HttpArchiveMediumAck)

  it should "handle incoming medium results" in:
    val res1 = mock[MediumProcessingResult]
    val res2 = mock[MediumProcessingResult]
    val propRes = PropagateMediumResult(mock[MediumProcessingResult], removeContent = false)
    val state2 = ProgressState.copy(mediaInProgress = 2, ack = Some(testActor),
      propagateMsg = Some(propRes))
    val helper = new HttpArchiveManagementActorTestHelper

    helper.stub(ProcessingStateTransitionData(None, None), ProgressState) { svc =>
      svc.handleResultAvailable(res1, testActor, PropBufSize)
    }.stub(ProcessingStateTransitionData(Some(propRes), Some(testActor)), state2) { svc =>
      svc.handleResultAvailable(res2, testActor, PropBufSize)
    }.post(res1)
      .expectStateUpdate(ContentProcessingUpdateServiceImpl.InitialState)
      .post(res2)
      .expectStateUpdate(ProgressState)
      .expectPropagation(propRes)
    expectMsg(HttpArchiveMediumAck)

  it should "handle confirmation about propagated media" in:
    val res = mock[MediumProcessingResult]
    val propRes = PropagateMediumResult(res, removeContent = false)
    val state2 = ProgressState.copy(mediaInProgress = 28)
    val helper = new HttpArchiveManagementActorTestHelper

    helper.stub(ProcessingStateTransitionData(Some(propRes), Some(testActor)),
      ProgressState)(_.handleResultPropagated(SeqNo, PropBufSize))
      .stub(ProcessingStateTransitionData(None, None), state2) { svc =>
        svc.handleResultAvailable(res, testActor, PropBufSize)
      }
      .post(MediumPropagated(SeqNo))
      .expectStateUpdate(ContentProcessingUpdateServiceImpl.InitialState)
      .expectPropagation(propRes)
      .post(res)
      .expectStateUpdate(ProgressState)
    expectMsg(HttpArchiveMediumAck)

  it should "handle a stream end notification" in:
    val state = ProgressState.copy(mediaInProgress = 3)
    val helper = new HttpArchiveManagementActorTestHelper

    helper.stub((), ProgressState)(_.processingDone())
      .stub(false, state)(_.processingStarts())
      .post(HttpArchiveProcessingComplete(HttpArchiveStateConnected))
      .expectStateUpdate(ContentProcessingUpdateServiceImpl.InitialState)
      .triggerScan(stubProcStarts = false)
      .expectStateUpdate(ProgressState)
      .checkArchiveState(HttpArchiveStateConnected)

  it should "handle and propagate a cancel message" in:
    val helper = new HttpArchiveManagementActorTestHelper
    helper.triggerScan().expectProcessingRequest()

    helper post CloseRequest
    expectMsg(CloseAck(helper.manager))
    helper.probeContentProcessor.expectMsg(CancelStreams)
    helper.probeMediumInfoProcessor.expectMsg(CancelStreams)
    helper.probeMetadataProcessor.expectMsg(CancelStreams)

  it should "forward a file download request to the download manager" in:
    val request = MediumFileRequest(MediaFileID(MediumID("aMedium", None), "fileUri"),
      withMetaData = true)
    val helper = new HttpArchiveManagementActorTestHelper

    helper.post(request)
    expectMsg(ForwardedMessage(request))

  it should "return the correct initial state" in:
    val helper = new HttpArchiveManagementActorTestHelper

    helper.checkArchiveState(HttpArchiveStateDisconnected)

  it should "switch to a success state as soon as data comes in" in:
    val state = ProgressState.copy(contentInArchive = true)
    val result = mock[MediumProcessingResult]
    val helper = new HttpArchiveManagementActorTestHelper

    helper
      .stub(ProcessingStateTransitionData(None, None), state) { svc =>
        svc.handleResultAvailable(result, testActor, PropBufSize)
      }
      .triggerScan()
      .post(result)
    helper.checkArchiveState(HttpArchiveStateConnected)

  it should "not answer a state request while loading data" in:
    implicit val timeout: Timeout = Timeout(100.millis)
    val helper = new HttpArchiveManagementActorTestHelper
    helper.triggerScan()

    val futState = helper.manager ? HttpArchiveStateRequest
    intercept[AskTimeoutException]:
      Await.result(futState, 10.seconds)

  it should "answer a state request when the state becomes available" in:
    val probe = TestProbe()
    val result = mock[MediumProcessingResult]
    val state = ProgressState.copy(contentInArchive = true)
    val helper = new HttpArchiveManagementActorTestHelper
    helper.stub(ProcessingStateTransitionData(None, None), state) { svc =>
      svc.handleResultAvailable(result, testActor, PropBufSize)
    }
      .triggerScan()
      .expectProcessingRequest()

    helper.manager.tell(HttpArchiveStateRequest, probe.ref)
    helper.manager ! HttpArchiveStateRequest
    helper.manager.tell(HttpArchiveStateRequest, probe.ref)
    helper post result
    val expState = HttpArchiveStateResponse(ArchiveName, HttpArchiveStateConnected)
    expectMsg(expState)
    probe.expectMsg(expState)
    expectNoMoreMsg(probe) // check that message was sent only once

  it should "reset the set of pending archive state clients" in:
    val probe = TestProbe()
    val state = ProgressState.copy(contentInArchive = true)
    val helper = new HttpArchiveManagementActorTestHelper
    helper.stub(ProcessingStateTransitionData(None, None),
      state)(_.handleResultPropagated(SeqNo, PropBufSize))
      .triggerScan()
      .expectProcessingRequest()
    helper.manager.tell(HttpArchiveStateRequest, probe.ref)
    helper.post(MediumPropagated(SeqNo))
    probe.expectMsgType[HttpArchiveStateResponse]

    helper.post(MediumPropagated(SeqNo))
    expectNoMoreMsg(probe)

  it should "return a server error state if the server could not be contacted" in:
    val helper = new HttpArchiveManagementActorTestHelper
    val exception = new IOException("Crashed")

    helper.stub((), ContentProcessingUpdateServiceImpl.InitialState)(_.processingDone())
      .initFailedArchiveResponse(exception)
      .triggerScan(initSuccessResponse = false)
      .checkArchiveState(HttpArchiveStateServerError(exception))
    verify(helper.updateService).processingDone()

  it should "return a request failed state if the request was not successful" in:
    val helper = new HttpArchiveManagementActorTestHelper
    val status = StatusCodes.Unauthorized
    val response = HttpResponse(status = status)

    helper.stub((), ContentProcessingUpdateServiceImpl.InitialState)(_.processingDone())
      .initFailedArchiveResponse(FailedResponseException(response))
      .triggerScan(initSuccessResponse = false)
      .checkArchiveState(HttpArchiveStateFailedRequest(status))
    verify(helper.updateService).processingDone()

  it should "forward a DownloadActorAlive notification to the monitoring actor" in:
    val aliveMsg = DownloadActorAlive(TestProbe().ref,
      MediaFileID(MediumID("testMediumURI", Some("settings.set")), "someFileUri"))
    val helper = new HttpArchiveManagementActorTestHelper

    helper.post(aliveMsg)
      .expectDownloadMonitoringMessage(aliveMsg)

  it should "answer a GetMetaDataFileInfo message with a dummy response" in:
    val helper = new HttpArchiveManagementActorTestHelper

    helper.post(GetMetaDataFileInfo)
    expectMsg(MetaDataFileInfo(Map.empty, Set.empty, None))

  /**
    * A test helper class managing all dependencies of a test actor instance.
    *
    * @param archiveConfig the archive configuration to be used
    */
  private class HttpArchiveManagementActorTestHelper(archiveConfig: HttpArchiveConfig = ArchiveConfig)
    extends StateTestHelper[ContentProcessingState, ContentProcessingUpdateService]:
    /** Mock for the update service. */
    override val updateService: ContentProcessingUpdateService = mock[ContentProcessingUpdateService]

    /** Test probe for the union media manager actor. */
    val probeUnionMediaManager: TestProbe = TestProbe()

    /** Test probe for the union metadata manager actor. */
    val probeUnionMetadataManager: TestProbe = TestProbe()

    /** Test probe for the content processor actor. */
    val probeContentProcessor: TestProbe = TestProbe()

    /** Test probe for the metadata processor actor. */
    val probeMetadataProcessor: TestProbe = TestProbe()

    /** Test probe for the medium info processor actor. */
    val probeMediumInfoProcessor: TestProbe = TestProbe()

    /** Test probe for the monitoring actor. */
    private val probeMonitoringActor = TestProbe()

    /** Test probe for the remove file actor. */
    private val probeRemoveActor = TestProbe()

    /** Test probe for the content propagation actor. */
    private val probeContentPropagationActor = TestProbe()

    /** Mock for the temp path generator. */
    private val pathGenerator = mock[TempPathGenerator]

    /** The actor simulating the download management actor. */
    private val downloadManagementActor = ForwardTestActor()

    /** The mock for the media downloader. */
    private val downloader = mock[MediaDownloader]

    /** A queue for recording the child actors that have been created. */
    private val childCreationQueue = new LinkedBlockingQueue[ChildActorCreation]

    /** The final configuration for the archive. */
    val config: HttpArchiveConfig = archiveConfig.copy(downloader = downloader)

    /** The actor to be tested. */
    val manager: TestActorRef[HttpArchiveManagementActor] = createTestActor()

    /**
      * Sends a message directly to the test actor.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def send(msg: Any): HttpArchiveManagementActorTestHelper =
      manager receive msg
      this

    /**
      * Sends a message via the ! method to the test actor.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def post(msg: Any): HttpArchiveManagementActorTestHelper =
      manager ! msg
      this

    /**
      * Sends a message to start a new scan to the test actor.
      *
      * @param stubProcStarts      flag whether processing start should be stubbed
      * @param initSuccessResponse flag whether the test request actor should
      *                            be configured to expect a successful request
      * @return this test helper
      */
    def triggerScan(stubProcStarts: Boolean = true, initSuccessResponse: Boolean = true):
    HttpArchiveManagementActorTestHelper =
      if stubProcStarts then
        stub(true, ProgressState) { svc => svc.processingStarts() }
      if initSuccessResponse then
        initSuccessArchiveResponse()
      send(ScanAllMedia)

    /**
      * Expects that a request to process the archive has been sent to the
      * content processor actor and returns the message.
      *
      * @return the request message
      */
    def expectProcessingRequest(): ProcessHttpArchiveRequest =
      probeContentProcessor.expectMsgType[ProcessHttpArchiveRequest]

    /**
      * Expects that no processing request is sent to the content processor
      * actor.
      *
      * @return this test helper
      */
    def expectNoProcessingRequest(): HttpArchiveManagementActorTestHelper =
      probeContentProcessor.expectNoMessage(1.second)
      this

    /**
      * Expects that a result was passed to the propagation actor.
      *
      * @param msg the propagation message
      * @return this test helper
      */
    def expectPropagation(msg: PropagateMediumResult): HttpArchiveManagementActorTestHelper =
      probeContentPropagationActor.expectMsg(msg)
      this

    /**
      * Prepares the mock request actor to return a successful response for a
      * request to the archive's content file.
      *
      * @return this test helper
      */
    def initSuccessArchiveResponse(): HttpArchiveManagementActorTestHelper =
      initArchiveContentResponse(Future.successful(createSuccessResponse()))

    /**
      * Prepares the mock request actor to return an exception response for a
      * request to the archive's content file.
      *
      * @param exception the exception to be returned as response
      * @return this test helper
      */
    def initFailedArchiveResponse(exception: Throwable): HttpArchiveManagementActorTestHelper =
      initArchiveContentResponse(Future.failed(exception))

    /**
      * Queries the current state from the test archive and compares it with
      * the expected state.
      *
      * @param expState the expected state
      * @return this test helper
      */
    def checkArchiveState(expState: HttpArchiveState): HttpArchiveManagementActorTestHelper =
      post(HttpArchiveStateRequest)
      val stateResponse = expectMsgType[HttpArchiveStateResponse]
      stateResponse.archiveName should be(ArchiveName)
      stateResponse.state should be(expState)
      this

    /**
      * Expects a message to be passed to the download monitoring actor.
      *
      * @param msg the expected message
      * @return this test helper
      */
    def expectDownloadMonitoringMessage(msg: Any): HttpArchiveManagementActorTestHelper =
      probeMonitoringActor.expectMsg(msg)
      this

    /**
      * Prepares the mock for the downloader to handle a download request for
      * the archive's main content file.
      *
      * @param result the result to answer the request
      * @return this test helper
      */
    private def initArchiveContentResponse(result: Future[Source[ByteString, Any]]):
    HttpArchiveManagementActorTestHelper =
      when(downloader.downloadMediaFile(ArchiveConfig.contentPath)).thenReturn(result)
      this

    /**
      * Creates the test actor.
      *
      * @return the test actor
      */
    private def createTestActor(): TestActorRef[HttpArchiveManagementActor] =
      TestActorRef(createProps())

    /**
      * Creates the properties for the test actor.
      *
      * @return creation Props for the test actor
      */
    private def createProps(): Props =
      Props(new HttpArchiveManagementActor(updateService, config, pathGenerator,
        probeUnionMediaManager.ref, probeUnionMetadataManager.ref,
        probeMonitoringActor.ref, probeRemoveActor.ref)
        with ChildActorFactory {

        /**
          * @inheritdoc Checks creation properties and returns test probes for
          *             the child actors
          */
        override def createChildActor(p: Props): ActorRef = {
          val child = p.actorClass() match {
            case ClsContentProcessor =>
              p.args should have size 0
              probeContentProcessor.ref

            case ClsMediumInfoProcessor =>
              p.args should have size 0
              probeMediumInfoProcessor.ref

            case ClsMetadataProcessor =>
              p.args should have size 0
              probeMetadataProcessor.ref

            case ClsDownloadManagementActor =>
              p.args should be(List(config, pathGenerator, probeMonitoringActor.ref, probeRemoveActor.ref))
              downloadManagementActor

            case ClsContentPropagationActor =>
              p.args should be(List(probeUnionMediaManager.ref, probeUnionMetadataManager.ref,
                ArchiveConfig.archiveName))
              probeContentPropagationActor.ref
          }
          childCreationQueue offer ChildActorCreation(p, child)
          child
        }
      })

