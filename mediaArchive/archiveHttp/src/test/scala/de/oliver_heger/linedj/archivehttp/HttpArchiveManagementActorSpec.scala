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

import java.io.IOException
import java.nio.file.Paths

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model._
import akka.pattern.{AskTimeoutException, ask}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.ForwardTestActor.ForwardedMessage
import de.oliver_heger.linedj.archivehttp.crypt.AESKeyGenerator
import de.oliver_heger.linedj.archivehttp.impl._
import de.oliver_heger.linedj.archivehttp.impl.crypt.{CryptHttpRequestActor, UriResolverActor}
import de.oliver_heger.linedj.archivehttp.impl.download.HttpDownloadManagementActor
import de.oliver_heger.linedj.archivehttp.impl.io.FailedRequestException
import de.oliver_heger.linedj.archivehttp.temp.TempPathGenerator
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor.CancelStreams
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.shared.archive.union.{UpdateOperationCompleted, UpdateOperationStarts}
import de.oliver_heger.linedj.utils.ChildActorFactory
import de.oliver_heger.linedj.{ForwardTestActor, StateTestHelper}
import org.mockito.Matchers.{any, eq => argEq}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._

object HttpArchiveManagementActorSpec {
  /** The value of the propagation buffer size config property. */
  private val PropBufSize = 4

  /** The test archive configuration. */
  private val ArchiveConfig = RequestActorTestImpl.createTestArchiveConfig().copy(propagationBufSize = PropBufSize)

  /** URI to the test music archive. */
  private val ArchiveURIStr = ArchiveConfig.archiveURI.toString()

  /** Constant for an archive name. */
  private val ArchiveName = ArchiveConfig.archiveName

  /** Constant for a sequence number. */
  private val SeqNo = 20180621

  /** The request to the test archive. */
  private val ArchiveRequest = createRequest()

  /** A key used by tests that simulate an encrypted archive. */
  private val CryptKey = new AESKeyGenerator().generateKey("MySecretArchive")

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
    HttpDownloadManagementActor(null, null, null, null, null).actorClass()

  /** Class for the actor that resolves encrypted URIs. */
  private val ClsUriResolverActor = classOf[UriResolverActor]

  /** Class for the actor that executes requests against encrypted archives. */
  private val ClsCryptRequestActor = classOf[CryptHttpRequestActor]

  /** A state indicating that a scan operation is in progress. */
  private val ProgressState = ContentProcessingUpdateServiceImpl.InitialState
    .copy(scanInProgress = true)

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
    HttpRequest(uri = ArchiveConfig.archiveURI)

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
      metaManager.ref, monitoringActor.ref, removeActor.ref, Some(CryptKey))
    classOf[HttpArchiveManagementActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(ContentProcessingUpdateServiceImpl, ArchiveConfig, pathGenerator,
      mediaManager.ref, metaManager.ref, monitoringActor.ref, removeActor.ref, Some(CryptKey)))
  }

  /**
    * Checks a processing operation on a test archive.
    *
    * @param helper the prepared helper
    */
  private def checkProcessing(helper: HttpArchiveManagementActorTestHelper): Unit = {
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

  it should "pass a process request to the content processor actor" in {
    checkProcessing(new HttpArchiveManagementActorTestHelper)
  }

  it should "correctly deal with encrypted archives" in {
    val helper = new HttpArchiveManagementActorTestHelper(cryptArchive = true)
    checkProcessing(helper)
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

    helper.initFailedArchiveResponse(new Exception)
      .triggerScan(initSuccessResponse = false)
      .expectNoProcessingRequest()
  }

  it should "recover from an error, so that a new request can be processed" in {
    val helper = new HttpArchiveManagementActorTestHelper

    helper.stub((), ProgressState)(_.processingDone())
      .initFailedArchiveResponse(new Exception)
      .triggerScan() // initializes a success response
      .triggerScan(stubProcStarts = false, initSuccessResponse = false)
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
    val exception = FailedRequestException(message = "Server not reachable!", response = None,
      cause = new IOException("Crashed"), data = 42)

    helper.stub((), ContentProcessingUpdateServiceImpl.InitialState)(_.processingDone())
      .initFailedArchiveResponse(exception)
      .triggerScan(initSuccessResponse = false)
      .checkArchiveState(HttpArchiveStateServerError(exception))
    verify(helper.updateService).processingDone()
  }

  it should "return a request failed state if the request was not successful" in {
    val helper = new HttpArchiveManagementActorTestHelper
    val status = StatusCodes.Unauthorized
    val response = HttpResponse(status = status)

    helper.stub((), ContentProcessingUpdateServiceImpl.InitialState)(_.processingDone())
      .initFailedArchiveResponse(FailedRequestException(response = Some(response), message = "Failure",
        cause = null, data = "some data"))
      .triggerScan(initSuccessResponse = false)
      .checkArchiveState(HttpArchiveStateFailedRequest(status))
    verify(helper.updateService).processingDone()
  }

  it should "forward a DownloadActorAlive notification to the monitoring actor" in {
    val aliveMsg = DownloadActorAlive(TestProbe().ref,
      MediaFileID(MediumID("testMediumURI", Some("settings.set")), "someFileUri"))
    val helper = new HttpArchiveManagementActorTestHelper

    helper.post(aliveMsg)
      .expectDownloadMonitoringMessage(aliveMsg)
  }

  /**
    * A test helper class managing all dependencies of a test actor instance.
    *
    * @param cryptArchive flag whether the test archive should be encrypted
    */
  private class HttpArchiveManagementActorTestHelper(cryptArchive: Boolean = false)
    extends StateTestHelper[ContentProcessingState, ContentProcessingUpdateService] {
    /** Mock for the update service. */
    override val updateService: ContentProcessingUpdateService = mock[ContentProcessingUpdateService]

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

    /** Test probe for the URI resolver actor. */
    private val probeUriResolverActor = TestProbe()

    /** The actor for sending requests. */
    private val plainRequestActor = system.actorOf(RequestActorTestImpl())

    /** The actor for sending encrypted requests. */
    private val cryptRequestActor = system.actorOf(RequestActorTestImpl())

    /** Mock for the temp path generator. */
    private val pathGenerator = mock[TempPathGenerator]

    /** The actor simulating the download management actor. */
    private val downloadManagementActor = ForwardTestActor()

    /** The actor to be tested. */
    val manager: TestActorRef[HttpArchiveManagementActor] = createTestActor()

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
      * @param stubProcStarts      flag whether processing start should be stubbed
      * @param initSuccessResponse flag whether the test request actor should
      *                            be configured to expect a successful request
      * @return this test helper
      */
    def triggerScan(stubProcStarts: Boolean = true, initSuccessResponse: Boolean = true):
    HttpArchiveManagementActorTestHelper = {
      if (stubProcStarts) {
        stub(true, ProgressState) { svc => svc.processingStarts() }
      }
      if (initSuccessResponse) {
        initSuccessArchiveResponse()
      }
      send(ScanAllMedia)
    }

    /**
      * Expects that a request to process the archive has been sent to the
      * content processor actor and returns the message.
      *
      * @return the request message
      */
    def expectProcessingRequest(): ProcessHttpArchiveRequest = {
      val request = probeContentProcessor.expectMsgType[ProcessHttpArchiveRequest]
      request.requestActor should be(requestActor)
      request
    }

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
      * Prepares the mock request actor to return a successful response for a
      * request to the archive's content file.
      *
      * @return this test helper
      */
    def initSuccessArchiveResponse(): HttpArchiveManagementActorTestHelper = {
      RequestActorTestImpl.expectRequest(requestActor, ArchiveRequest, createSuccessResponse())
      this
    }

    /**
      * Prepares the mock request actor to return an exception response for a
      * request to the archive's content file.
      *
      * @param exception the exception to be returned as response
      * @return this test helper
      */
    def initFailedArchiveResponse(exception: Throwable): HttpArchiveManagementActorTestHelper = {
      RequestActorTestImpl.expectFailedRequest(requestActor, ArchiveRequest, exception)
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
      * Returns the actor to be used for sending HTTP requests. This actor
      * depends on the encryption state of the archive.
      *
      * @return the actor for sending HTTP requests
      */
    private def requestActor: ActorRef =
      if (cryptArchive) cryptRequestActor else plainRequestActor

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
        probeMonitoringActor.ref, probeRemoveActor.ref,
        if (cryptArchive) Some(CryptKey) else None)
        with ChildActorFactory {

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
              p.args should be(List(ArchiveConfig, pathGenerator, requestActor, probeMonitoringActor.ref,
                probeRemoveActor.ref))
              downloadManagementActor

            case ClsContentPropagationActor =>
              p.args should be(List(probeUnionMediaManager.ref, probeUnionMetaDataManager.ref,
                ArchiveURIStr))
              probeContentPropagationActor.ref

            case ClsUriResolverActor if cryptArchive =>
              p.args should be(List(plainRequestActor, CryptKey, RequestActorTestImpl.TestArchiveBasePath,
                ArchiveConfig.cryptUriCacheSize))
              probeUriResolverActor.ref

            case RequestActorTestImpl.ClsRequestActor =>
              p.args should contain only ArchiveConfig
              plainRequestActor

            case ClsCryptRequestActor if cryptArchive =>
              p.args should be(List(probeUriResolverActor.ref, plainRequestActor, CryptKey,
                ArchiveConfig.processorTimeout))
              cryptRequestActor
          }
      })
  }

}
