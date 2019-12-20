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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model._
import akka.pattern.{AskTimeoutException, ask}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.ForwardTestActor.ForwardedMessage
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig.AuthConfigureFunc
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.archivehttp.crypt.{AESKeyGenerator, Secret}
import de.oliver_heger.linedj.archivehttp.http.HttpRequests
import de.oliver_heger.linedj.archivehttp.impl._
import de.oliver_heger.linedj.archivehttp.impl.crypt.{CryptHttpRequestActor, UriResolverActor}
import de.oliver_heger.linedj.archivehttp.impl.download.HttpDownloadManagementActor
import de.oliver_heger.linedj.archivehttp.impl.io.{FailedRequestException, HttpBasicAuthRequestActor, HttpCookieManagementActor, HttpMultiHostRequestActor}
import de.oliver_heger.linedj.archivehttp.spi.HttpArchiveProtocol
import de.oliver_heger.linedj.archivehttp.temp.TempPathGenerator
import de.oliver_heger.linedj.io.stream.AbstractStreamProcessingActor.CancelStreams
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.shared.archive.union.{UpdateOperationCompleted, UpdateOperationStarts}
import de.oliver_heger.linedj.utils.ChildActorFactory
import de.oliver_heger.linedj.{AsyncTestHelper, ForwardTestActor, StateTestHelper}
import org.mockito.Matchers.{any, eq => argEq}
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.reflect.ClassTag

object HttpArchiveManagementActorSpec {
  /** An auth function that does not special configuration. */
  private val NoAuthFunc: AuthConfigureFunc = (actor, _) => actor

  /** The value of the propagation buffer size config property. */
  private val PropBufSize = 4

  /**
    * The template for the test archive configuration. (Some properties are
    * modified by specific tests.
    */
  private val ArchiveConfig = RequestActorTestImpl.createTestArchiveConfig()
    .copy(propagationBufSize = PropBufSize, authFunc = NoAuthFunc)

  /** URI to the test music archive. */
  private val ArchiveURIStr = ArchiveConfig.archiveURI.toString()

  /** Constant for an archive name. */
  private val ArchiveName = ArchiveConfig.archiveName

  /** Constant for a sequence number. */
  private val SeqNo = 20180621

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

  /** Class for the actor that handles basic authentication. */
  private val ClsBasicAuthActor = classOf[HttpBasicAuthRequestActor]

  /** Class for the actor that implements cookie management. */
  private val ClsCookieManagementActor = classOf[HttpCookieManagementActor]

  /** Class for the multi host request actor. */
  private val ClsMultiHostActor = classOf[HttpMultiHostRequestActor]

  /** A state indicating that a scan operation is in progress. */
  private val ProgressState = ContentProcessingUpdateServiceImpl.InitialState
    .copy(scanInProgress = true)

  /** A dummy request that it is used where it is needed. */
  private val TestSendRequest = HttpRequests.SendRequest(HttpRequest(), 42)

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
    * Creates a response object for a successful request to the content
    * document of the test archive.
    *
    * @return the success response
    */
  private def createSuccessResponse(): HttpResponse =
    HttpResponse(status = StatusCodes.OK, entity = createMediumDescriptionsJson())

  /**
    * Helper function to filter out all child actors whose class is the same as
    * the specified one.
    *
    * @param actorClass the desired actor class
    * @param children   the list of child actors
    * @return the filtered list of child actors of the given class
    */
  private def childActorsOfClass(actorClass: Class[_], children: Seq[ChildActorCreation]): Seq[ChildActorCreation] =
    children.filter(c => c.props.actorClass() == actorClass)

  /**
    * Helper function to find the child actors of a specific type.
    *
    * @param children the list of child actors
    * @param ct       the class tag for the actor class
    * @tparam T the type of the actor class
    * @return a list with the child actors of the given class
    */
  private def childActorsOfType[T](children: Seq[ChildActorCreation])(implicit ct: ClassTag[T]):
  Seq[ChildActorCreation] =
    childActorsOfClass(ct.runtimeClass, children)
}

/**
  * Test class for ''HttpArchiveManagementActor''.
  */
class HttpArchiveManagementActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar with AsyncTestHelper {

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
    request.archiveConfig should be(helper.config)
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

  it should "create only a minimum set of child actors" in {
    val helper = new HttpArchiveManagementActorTestHelper
    checkProcessing(helper)

    val actorCreations = helper.childActorCreations
    childActorsOfType[HttpCookieManagementActor](actorCreations) should have size 0
    childActorsOfType[HttpBasicAuthRequestActor](actorCreations) should have size 0
    childActorsOfType[HttpCookieManagementActor](actorCreations) should have size 0
    childActorsOfType[CryptHttpRequestActor](actorCreations) should have size 0
    childActorsOfClass(RequestActorTestImpl.ClsRequestActor, actorCreations) should have size 1
  }

  it should "create a correct request actor for basic auth" in {
    val credentials = UserCredentials("scott", Secret("tiger"))
    val authFunc = futureResult(HttpArchiveManagementActor.basicAuthConfigureFunc(credentials))
    val config = ArchiveConfig.copy(authFunc = authFunc)
    val helper = new HttpArchiveManagementActorTestHelper(archiveConfig = config)
    checkProcessing(helper)

    val actorCreations = helper.childActorCreations
    val requestActor = childActorsOfClass(RequestActorTestImpl.ClsRequestActor, actorCreations).head
    childActorsOfType[HttpBasicAuthRequestActor](actorCreations) match {
      case h :: t if t.isEmpty =>
        h.props.args should contain theSameElementsInOrderAs List(credentials, requestActor.child)
    }
  }

  it should "create a correct request actor if cookie management is enabled" in {
    val config = ArchiveConfig.copy(needsCookieManagement = true)
    val helper = new HttpArchiveManagementActorTestHelper(archiveConfig = config)
    checkProcessing(helper)

    childActorsOfType[HttpCookieManagementActor](helper.childActorCreations) should have size 1
  }

  it should "create a correct request actor if multi-host support is needed" in {
    val helper = new HttpArchiveManagementActorTestHelper(multiHost = true)
    checkProcessing(helper)

    childActorsOfType[HttpMultiHostRequestActor](helper.childActorCreations) should have size 1
  }

  it should "correctly combine multiple aspects when creating the request actor" in {
    val authFunc = futureResult(HttpArchiveManagementActor.basicAuthConfigureFunc(
      UserCredentials("scott", Secret("tiger"))))
    val config = ArchiveConfig.copy(needsCookieManagement = true,
      authFunc = authFunc)
    val helper = new HttpArchiveManagementActorTestHelper(archiveConfig = config, cryptArchive = true)
    checkProcessing(helper)

    val actorCreations = helper.childActorCreations
    childActorsOfType[CryptHttpRequestActor](actorCreations) should have size 1
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
      cause = new IOException("Crashed"), request = TestSendRequest)

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
        cause = null, request = TestSendRequest))
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
    * @param cryptArchive  flag whether the test archive should be encrypted
    * @param multiHost     flag whether the protocol needs multiple hosts
    * @param archiveConfig the archive configuration to be used
    */
  private class HttpArchiveManagementActorTestHelper(cryptArchive: Boolean = false,
                                                     multiHost: Boolean = false,
                                                     archiveConfig: HttpArchiveConfig = ArchiveConfig)
    extends StateTestHelper[ContentProcessingState, ContentProcessingUpdateService] {
    /** Mock for the update service. */
    override val updateService: ContentProcessingUpdateService = mock[ContentProcessingUpdateService]

    /** Test probe for the union media manager actor. */
    val probeUnionMediaManager: TestProbe = TestProbe()

    /** Test probe for the union meta data manager actor. */
    val probeUnionMetaDataManager: TestProbe = TestProbe()

    /** Test probe for the content processor actor. */
    val probeContentProcessor: TestProbe = TestProbe()

    /** Test probe for the meta data processor actor. */
    val probeMetaDataProcessor: TestProbe = TestProbe()

    /** Test probe for the medium info processor actor. */
    val probeMediumInfoProcessor: TestProbe = TestProbe()

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

    /** The actor for sending basic auth requests. */
    private val basicAuthRequestActor = system.actorOf(RequestActorTestImpl())

    /** The actor for managing cookies. */
    private val cookieManagementActor = system.actorOf(RequestActorTestImpl())

    /** The actor for sending encrypted requests. */
    private val cryptRequestActor = system.actorOf(RequestActorTestImpl())

    private val multiHostRequestActor = system.actorOf(RequestActorTestImpl())

    /** Mock for the temp path generator. */
    private val pathGenerator = mock[TempPathGenerator]

    /** The actor simulating the download management actor. */
    private val downloadManagementActor = ForwardTestActor()

    /** The mock for the archive protocol. */
    private val httpProtocol = createProtocol()

    /** A queue for recording the child actors that have been created. */
    private val childCreationQueue = new LinkedBlockingQueue[ChildActorCreation]

    /**
      * Stores the current request actor to be used. As request actors can be
      * decorated, the reference changes depending on the setup of decorators.
      */
    private val refRequestActor = new AtomicReference[ActorRef]

    /** The final configuration for the archive. */
    val config: HttpArchiveConfig = archiveConfig.copy(protocol = httpProtocol)

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
    def initSuccessArchiveResponse(): HttpArchiveManagementActorTestHelper =
      initArchiveContentResponse(Future.successful(HttpRequests.ResponseData(createSuccessResponse(), null)))

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
      * Returns a list with information about child actors that have been
      * created by the management actor.
      *
      * @return a list with information about child actors
      */
    def childActorCreations: List[ChildActorCreation] = {
      @scala.annotation.tailrec
      def addNextChildActor(list: List[ChildActorCreation]): List[ChildActorCreation] = {
        val child = childCreationQueue.poll()
        if (child == null) list
        else addNextChildActor(child :: list)
      }

      addNextChildActor(Nil)
    }

    /**
      * Returns the actor to be used for sending HTTP requests. This actor
      * depends on the encryption state of the archive and the decorators that
      * have been configured.
      *
      * @return the actor for sending HTTP requests
      */
    private def requestActor: ActorRef = refRequestActor.get()

    /**
      * Creates the mock for the HTTP protocol and configures it according to
      * the parameters passed to the constructor.
      *
      * @return the mock protocol
      */
    private def createProtocol(): HttpArchiveProtocol = {
      val protocol = mock[HttpArchiveProtocol]
      when(protocol.requiresMultiHostSupport).thenReturn(multiHost)
      protocol
    }

    /**
      * Prepares the mock for the HTTP protocol to handle a download request
      * for the archive's main content file.
      *
      * @param result the result to answer the request
      * @return
      */
    private def initArchiveContentResponse(result: Future[HttpRequests.ResponseData]):
    HttpArchiveManagementActorTestHelper = {
      when(httpProtocol.downloadMediaFile(argEq(requestActor), argEq(config.archiveURI))(any(), any(),
        argEq(config.processorTimeout))).thenReturn(result)
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
      Props(new HttpArchiveManagementActor(updateService, config, pathGenerator,
        probeUnionMediaManager.ref, probeUnionMetaDataManager.ref,
        probeMonitoringActor.ref, probeRemoveActor.ref,
        if (cryptArchive) Some(CryptKey) else None)
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

            case ClsMetaDataProcessor =>
              p.args should have size 0
              probeMetaDataProcessor.ref

            case ClsDownloadManagementActor =>
              p.args should be(List(config, pathGenerator, requestActor, probeMonitoringActor.ref,
                probeRemoveActor.ref))
              downloadManagementActor

            case ClsContentPropagationActor =>
              p.args should be(List(probeUnionMediaManager.ref, probeUnionMetaDataManager.ref,
                ArchiveURIStr))
              probeContentPropagationActor.ref

            case ClsUriResolverActor if cryptArchive =>
              p.args should be(List(requestActor, httpProtocol, CryptKey,
                RequestActorTestImpl.TestArchiveBasePath, ArchiveConfig.cryptUriCacheSize))
              probeUriResolverActor.ref

            case RequestActorTestImpl.ClsRequestActor =>
              refRequestActor.get() should be(null)
              p.args should contain only(ArchiveConfig.archiveURI, ArchiveConfig.requestQueueSize)
              refRequestActor.set(plainRequestActor)
              plainRequestActor

            case ClsCryptRequestActor if cryptArchive =>
              p.args should be(List(probeUriResolverActor.ref, requestActor, CryptKey,
                ArchiveConfig.processorTimeout))
              refRequestActor.set(cryptRequestActor)
              cryptRequestActor

            case ClsBasicAuthActor =>
              refRequestActor.set(basicAuthRequestActor)
              basicAuthRequestActor

            case ClsCookieManagementActor =>
              p.args should contain only requestActor
              refRequestActor.set(cookieManagementActor)
              cookieManagementActor

            case ClsMultiHostActor =>
              refRequestActor.get() should be(null)
              refRequestActor.set(multiHostRequestActor)
              p.args should be(List(HttpArchiveManagementActor.MultiHostCacheSize, config.requestQueueSize))
              multiHostRequestActor
          }
          childCreationQueue offer ChildActorCreation(p, child)
          child
        }
      })
  }

}
