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

package de.oliver_heger.linedj.archivehttp

import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.pattern.{AskTimeoutException, ask}
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.ForwardTestActor.ForwardedMessage
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.archivehttp.impl._
import de.oliver_heger.linedj.archivehttp.impl.download.HttpDownloadManagementActor
import de.oliver_heger.linedj.archivehttp.impl.io.{FailedRequestException, HttpFlowFactory, HttpRequestSupport}
import de.oliver_heger.linedj.archivehttp.temp.TempPathGenerator
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor.CancelStreams
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.shared.archive.union.{UpdateOperationCompleted, UpdateOperationStarts}
import de.oliver_heger.linedj.utils.{ChildActorFactory, SystemPropertyAccess}
import de.oliver_heger.linedj.{ForwardTestActor, StateTestHelper}
import org.mockito.Matchers.{any, eq => argEq}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

object HttpArchiveManagementActorSpec {
  /** URI to the test music archive. */
  private val ArchiveURIStr = "https://my.music.la/content.json"

  /** Constant for an archive name. */
  private val ArchiveName = "MyMusic"

  /** The value of the propagation buffer size config property. */
  private val PropBufSize = 4

  /** Constant for a sequence number. */
  private val SeqNo = 20180621

  /** The test archive configuration. */
  private val ArchiveConfig = createArchiveConfig()

  /** The request to the test archive. */
  private val ArchiveRequest = createRequest()

  /** Class for the content processor actor. */
  private val ClsContentProcessor = classOf[HttpArchiveContentProcessorActor]

  /** Class for the medium info processor actor. */
  private val ClsMediumInfoProcessor = classOf[MediumInfoResponseProcessingActor]

  /** Class for the meta data processor actor. */
  private val ClsMetaDataProcessor = classOf[MetaDataResponseProcessingActor]

  /** Class for the content propagation actor. */
  private val ClsContentPropagationActor = classOf[ContentPropagationActor]

  /** Class for the download management actor. */
  private val ClsDownloadManagementActor =
    HttpDownloadManagementActor(null, null, null, null).actorClass()

  /** A state indicating that a scan operation is in progress. */
  private val ProgressState = ContentProcessingUpdateServiceImpl.InitialState
    .copy(scanInProgress = true)

  /**
    * Creates a test configuration for a media archive.
    *
    * @return the test configuration
    */
  private def createArchiveConfig(): HttpArchiveConfig =
    HttpArchiveConfig(archiveURI = Uri(ArchiveURIStr), processorCount = 2,
      maxContentSize = 256, processorTimeout = Timeout(1.minute),
      credentials = UserCredentials("scott", "tiger"), downloadConfig = null,
      downloadBufferSize = 1024, downloadMaxInactivity = 1.minute,
      downloadReadChunkSize = 4000, timeoutReadSize = 2222, archiveName = ArchiveName,
      metaMappingConfig = null, contentMappingConfig = null, propagationBufSize = PropBufSize,
      requestQueueSize = 16)

  /**
    * Checks that no further messages have been sent to the specified test
    * probe.
    *
    * @param probe the test probe
    */
  private def expectNoMoreMsg(probe: TestProbe): Unit = {
    val msg = new Object
    probe.ref ! msg
    probe.expectMsg(msg)
  }

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
    * Creates a request to the content file of the test archive.
    *
    * @return the request to the test archive
    */
  private def createRequest(): HttpRequest =
    HttpRequest(uri = ArchiveConfig.archiveURI,
      headers = List(Authorization(BasicHttpCredentials(ArchiveConfig.credentials.userName,
        ArchiveConfig.credentials.password))))

  /**
    * Creates a response object for a successful request to the content
    * document of the test archive.
    *
    * @return the success response
    */
  private def createSuccessResponse(): HttpResponse =
    HttpResponse(status = StatusCodes.OK, entity = createMediumDescriptionsJson())

}

/**
  * Test class for ''HttpArchiveManagementActor''.
  */
class HttpArchiveManagementActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import HttpArchiveManagementActorSpec._

  def this() = this(ActorSystem("HttpArchiveManagementActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A HttpArchiveManagementActor" should "create correct Props" in {
    val mediaManager = TestProbe()
    val metaManager = TestProbe()
    val monitoringActor = TestProbe()
    val removeActor = TestProbe()
    val pathGenerator = new TempPathGenerator(Paths get "temp")
    val props = HttpArchiveManagementActor(ArchiveConfig, pathGenerator, mediaManager.ref,
      metaManager.ref, monitoringActor.ref, removeActor.ref)
    classOf[HttpArchiveManagementActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[HttpFlowFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[HttpRequestSupport[RequestData]].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(ContentProcessingUpdateServiceImpl, ArchiveConfig, pathGenerator,
      mediaManager.ref, metaManager.ref, monitoringActor.ref, removeActor.ref))
  }

  it should "pass a process request to the content processor actor" in {
    val helper = new HttpArchiveManagementActorTestHelper

    val request = helper.stub((), ProgressState)(_.processingDone())
      .triggerScan().expectProcessingRequest()
    request.archiveConfig should be(ArchiveConfig)
    request.settingsProcessorActor should be(helper.probeMediumInfoProcessor.ref)
    request.metaDataProcessorActor should be(helper.probeMetaDataProcessor.ref)
    implicit val mat: ActorMaterializer = ActorMaterializer()

    val futureDescriptions = request.mediaSource.runFold(
      List.empty[HttpMediumDesc])((lst, md) => md :: lst)
    val descriptions = Await.result(futureDescriptions, 5.seconds)
    val expDescriptions = createMediumDescriptions()
    descriptions should contain only (expDescriptions: _*)

    val result = mock[MediumProcessingResult]
    helper.stub(ProcessingStateTransitionData(None, None), mock[ContentProcessingState]) { svc =>
      svc.handleResultAvailable(argEq(result), any(), argEq(PropBufSize))
    }
    val srcResults = Source.single(result)
    srcResults.runWith(request.sink)
    helper.expectStateUpdate(ContentProcessingUpdateServiceImpl.InitialState)
      .expectStateUpdate(ProgressState)
    verify(helper.updateService).handleResultAvailable(argEq(result), any(), argEq(PropBufSize))
  }

  it should "notify the union archive about a started scan operation" in {
    val helper = new HttpArchiveManagementActorTestHelper

    helper.stub((), ProgressState)(_.processingDone())
      .triggerScan()
    helper.probeUnionMetaDataManager.expectMsg(UpdateOperationStarts(None))
  }

  it should "notify the union archive about a completed scan operation" in {
    val helper = new HttpArchiveManagementActorTestHelper

    helper.stub((), ProgressState)(_.processingDone())
      .post(HttpArchiveProcessingComplete(HttpArchiveStateConnected))
    helper.probeUnionMetaDataManager.expectMsg(UpdateOperationCompleted(None))
  }

  it should "not send a process request for a response error" in {
    val helper = new HttpArchiveManagementActorTestHelper

    helper.initArchiveResponse(Future.failed(new Exception)).triggerScan()
      .expectNoProcessingRequest()
  }

  it should "recover from an error, so that a new request can be processed" in {
    val helper = new HttpArchiveManagementActorTestHelper

    helper.stub((), ProgressState)(_.processingDone())
      .initArchiveResponse(Future.failed(new Exception))
      .triggerScan()
      .initArchiveResponse(null)
      .triggerScan(stubProcStarts = false)
      .expectProcessingRequest()
  }

  it should "ignore a process request if a scan is ongoing" in {
    val helper = new HttpArchiveManagementActorTestHelper

    helper.stub(false, ProgressState)(_.processingStarts())
      .triggerScan(stubProcStarts = false)
      .expectNoProcessingRequest()
    expectNoMoreMsg(helper.probeUnionMetaDataManager)
  }

  it should "answer an archive processing init message" in {
    val helper = new HttpArchiveManagementActorTestHelper

    helper.post(HttpArchiveProcessingInit)
    expectMsg(HttpArchiveMediumAck)
  }

  it should "handle incoming medium results" in {
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
  }

  it should "handle confirmation about propagated media" in {
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
  }

  it should "handle a stream end notification" in {
    val state = ProgressState.copy(mediaInProgress = 3)
    val helper = new HttpArchiveManagementActorTestHelper

    helper.stub((), ProgressState)(_.processingDone())
      .stub(false, state)(_.processingStarts())
      .post(HttpArchiveProcessingComplete(HttpArchiveStateConnected))
      .expectStateUpdate(ContentProcessingUpdateServiceImpl.InitialState)
      .triggerScan(stubProcStarts = false)
      .expectStateUpdate(ProgressState)
      .checkArchiveState(HttpArchiveStateConnected)
  }

  it should "handle and propagate a cancel message" in {
    val helper = new HttpArchiveManagementActorTestHelper
    helper.triggerScan().expectProcessingRequest()

    helper post CloseRequest
    expectMsg(CloseAck(helper.manager))
    helper.probeContentProcessor.expectMsg(CancelStreams)
    helper.probeMediumInfoProcessor.expectMsg(CancelStreams)
    helper.probeMetaDataProcessor.expectMsg(CancelStreams)
  }

  it should "forward a file download request to the download manager" in {
    val request = MediumFileRequest(MediaFileID(MediumID("aMedium", None), "fileUri"),
      withMetaData = true)
    val helper = new HttpArchiveManagementActorTestHelper

    helper.post(request)
    expectMsg(ForwardedMessage(request))
  }

  it should "return the correct initial state" in {
    val helper = new HttpArchiveManagementActorTestHelper

    helper.checkArchiveState(HttpArchiveStateDisconnected)
  }

  it should "switch to a success state as soon as data comes in" in {
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
  }

  it should "not answer a state request while loading data" in {
    implicit val timeout: Timeout = Timeout(100.millis)
    val helper = new HttpArchiveManagementActorTestHelper
    helper.triggerScan()

    val futState = helper.manager ? HttpArchiveStateRequest
    intercept[AskTimeoutException] {
      Await.result(futState, 10.seconds)
    }
  }

  it should "answer a state request when the state becomes available" in {
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
  }

  it should "reset the set of pending archive state clients" in {
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
  }

  it should "return a server error state if the server could not be contacted" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val exception = new Exception("Server not reachable!")

    helper.stub((), ContentProcessingUpdateServiceImpl.InitialState)(_.processingDone())
      .initArchiveResponse(Future.failed(exception))
      .triggerScan()
      .checkArchiveState(HttpArchiveStateServerError(exception))
    verify(helper.updateService).processingDone()
  }

  it should "return a request failed state if the request was not successful" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val status = StatusCodes.Unauthorized
    val response = HttpResponse(status = status)

    helper.stub((), ContentProcessingUpdateServiceImpl.InitialState)(_.processingDone())
      .initArchiveResponse(Future.failed(FailedRequestException(response)))
      .triggerScan()
      .checkArchiveState(HttpArchiveStateFailedRequest(status))
    verify(helper.updateService).processingDone()
  }

  it should "forward a DownloadActorAlive notification to the monitoring actor" in {
    val aliveMsg = DownloadActorAlive(TestProbe().ref,
      MediumID("testMediumURI", Some("settings.set")))
    val helper = new HttpArchiveManagementActorTestHelper

    helper.post(aliveMsg)
      .expectDownloadMonitoringMessage(aliveMsg)
  }

  /**
    * A test helper class managing all dependencies of a test actor instance.
    */
  private class HttpArchiveManagementActorTestHelper
    extends StateTestHelper[ContentProcessingState, ContentProcessingUpdateService] {
    /** Mock for the update service. */
    override val updateService = mock[ContentProcessingUpdateService]

    /** Test probe for the union media manager actor. */
    val probeUnionMediaManager = TestProbe()

    /** Test probe for the union meta data manager actor. */
    val probeUnionMetaDataManager = TestProbe()

    /** Test probe for the content processor actor. */
    val probeContentProcessor = TestProbe()

    /** Test probe for the meta data processor actor. */
    val probeMetaDataProcessor = TestProbe()

    /** Test probe for the medium info processor actor. */
    val probeMediumInfoProcessor = TestProbe()

    /** Test probe for the monitoring actor. */
    private val probeMonitoringActor = TestProbe()

    /** Test probe for the remove file actor. */
    private val probeRemoveActor = TestProbe()

    /** Test probe for the content propagation actor. */
    private val probeContentPropagationActor = TestProbe()

    /** Mock for the temp path generator. */
    private val pathGenerator = mock[TempPathGenerator]

    /** Mock for the HTTP flow returned by the mock factory implementation. */
    private val httpFlow = createTestHttpFlow[RequestData]()

    /** The actor simulating the download management actor. */
    private val downloadManagementActor = ForwardTestActor()

    /** The actor to be tested. */
    val manager: TestActorRef[HttpArchiveManagementActor] = createTestActor()

    /**
      * Stores a future response to be returned for a request of the archive's
      * content file.
      */
    private val archiveResponse = new AtomicReference[Future[(HttpResponse, RequestData)]]

    /**
      * Sends a message directly to the test actor.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def send(msg: Any): HttpArchiveManagementActorTestHelper = {
      manager receive msg
      this
    }

    /**
      * Sends a message via the ! method to the test actor.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def post(msg: Any): HttpArchiveManagementActorTestHelper = {
      manager ! msg
      this
    }

    /**
      * Sends a message to start a new scan to the test actor.
      *
      * @param stubProcStarts flag whether processing start should be stubbed
      * @return this test helper
      */
    def triggerScan(stubProcStarts: Boolean = true): HttpArchiveManagementActorTestHelper = {
      if (stubProcStarts) {
        stub(true, ProgressState) { svc => svc.processingStarts() }
      }
      send(ScanAllMedia)
    }

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
    def expectNoProcessingRequest(): HttpArchiveManagementActorTestHelper = {
      probeContentProcessor.expectNoMessage(1.second)
      this
    }

    /**
      * Expects that a result was passed to the propagation actor.
      *
      * @param msg the propagation message
      * @return this test helper
      */
    def expectPropagation(msg: PropagateMediumResult): HttpArchiveManagementActorTestHelper = {
      probeContentPropagationActor.expectMsg(msg)
      this
    }

    /**
      * Allows setting an explicit response to be returned by the request to
      * the HTTP archive's content file. If no response was set, a successful
      * response is generated automatically.
      *
      * @param response the future with the response
      * @return this test helper
      */
    def initArchiveResponse(response: Future[(HttpResponse, RequestData)]):
    HttpArchiveManagementActorTestHelper = {
      archiveResponse.set(response)
      this
    }

    /**
      * Queries the current state from the test archive and compares it with
      * the expected state.
      *
      * @param expState the expected state
      * @return this test helper
      */
    def checkArchiveState(expState: HttpArchiveState): HttpArchiveManagementActorTestHelper = {
      post(HttpArchiveStateRequest)
      val stateResponse = expectMsgType[HttpArchiveStateResponse]
      stateResponse.archiveName should be(ArchiveName)
      stateResponse.state should be(expState)
      this
    }

    /**
      * Expects a message to be passed to the download monitoring actor.
      *
      * @param msg the expected message
      * @return this test helper
      */
    def expectDownloadMonitoringMessage(msg: Any): HttpArchiveManagementActorTestHelper = {
      probeMonitoringActor.expectMsg(msg)
      this
    }

    /**
      * Expects that the given state was passed to the update service.
      *
      * @param state the expected state
      * @return this test helper
      */
    def expectStateUpdate(state: ContentProcessingState): HttpArchiveManagementActorTestHelper = {
      nextUpdatedState().get should be(state)
      this
    }

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
      Props(new HttpArchiveManagementActor(updateService, ArchiveConfig, pathGenerator,
        probeUnionMediaManager.ref, probeUnionMetaDataManager.ref,
        probeMonitoringActor.ref, probeRemoveActor.ref)
        with ChildActorFactory with HttpFlowFactory with SystemPropertyAccess
        with HttpRequestSupport[RequestData] {
        override def createHttpFlow[T](uri: Uri)(implicit mat: Materializer, system: ActorSystem):
        Flow[(HttpRequest, T), (Try[HttpResponse], T), Any] = {
          uri should be(ArchiveConfig.archiveURI)
          system should be(testSystem)
          mat should not be null
          httpFlow.asInstanceOf[Flow[(HttpRequest, T), (Try[HttpResponse], T), Any]]
        }

        /**
          * @inheritdoc This implementation returns a test response.
          */
        override def sendRequest(request: HttpRequest, data: RequestData, flow: Flow[
          (HttpRequest, RequestData), (Try[HttpResponse], RequestData), Any])
                                (implicit mat: Materializer, ec: ExecutionContext):
        Future[(HttpResponse, RequestData)] = {
          mat should not be null
          ec should not be null
          data.mediumDesc should be(null)
          request should be(ArchiveRequest)
          flow should be(httpFlow)
          val resp = archiveResponse.get()
          if (resp != null) resp else Future((createSuccessResponse(), data))
        }

        /**
          * @inheritdoc Checks creation properties and returns test probes for
          *             the child actors
          */
        override def createChildActor(p: Props): ActorRef =
          p.actorClass() match {
            case ClsContentProcessor =>
              p.args should have size 0
              probeContentProcessor.ref

            case ClsMediumInfoProcessor =>
              p.args should have size 0
              probeMediumInfoProcessor.ref

            case ClsMetaDataProcessor =>
              p.args should have size 0
              probeMetaDataProcessor.ref

            case ClsDownloadManagementActor =>
              p.args should be(List(ArchiveConfig, pathGenerator, probeMonitoringActor.ref,
                probeRemoveActor.ref))
              downloadManagementActor

            case ClsContentPropagationActor =>
              p.args should be(List(probeUnionMediaManager.ref, probeUnionMetaDataManager.ref,
                ArchiveURIStr))
              probeContentPropagationActor.ref
          }
      })

    /**
      * Creates the test HTTP flow. This flow is not actually invoked; so only
      * a dummy is returned.
      *
      * @return the test HTTP flow
      */
    private def createTestHttpFlow[T](): Flow[(HttpRequest, T), (Try[HttpResponse], T), NotUsed] = {
      Flow.fromFunction[(HttpRequest, T), (Try[HttpResponse], T)] { req =>
        (Try(HttpResponse()), req._2)
      }
    }
  }

}
