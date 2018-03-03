/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.impl.download

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import de.oliver_heger.linedj.archivecommon.download.DownloadMonitoringActor.DownloadOperationStarted
import de.oliver_heger.linedj.archivecommon.download.MediaFileDownloadActor
import de.oliver_heger.linedj.archivecommon.download.MediaFileDownloadActor.DownloadTransformFunc
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.archivehttp.impl.download.HttpDownloadManagementActor.DownloadOperationRequest
import de.oliver_heger.linedj.archivehttp.impl.io.{HttpFlowFactory, HttpRequestSupport}
import de.oliver_heger.linedj.archivehttp.temp.TempPathGenerator
import de.oliver_heger.linedj.extract.id3.processor.ID3v2ProcessingStage
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumFileRequest, MediumFileResponse, MediumID}
import de.oliver_heger.linedj.utils.{ChildActorFactory, SystemPropertyAccess}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object HttpDownloadManagementActorSpec {
  /** The URI of the test archive. */
  private val ArchiveUri = "https://my.cool.music.archive.io/cool/music.json"

  /** The URI of a test file to be downloaded. */
  private val DownloadUri = "https://my.cool.music.archive.io/cool/medium/song.mp3"

  /** A test medium ID. */
  private val TestMedium = MediumID("testMedium", Some("description"))

  private val Credentials = UserCredentials("foo", "bar")

  /**
    * A data class storing information about child actors created by the test
    * actor.
    *
    * @param child the probe representing the child actor
    * @param props the ''Props'' passed for actor creation
    */
  private case class ChildCreationData(child: TestProbe, props: Props)

}

/**
  * Test class for ''HttpDownloadManagementActor''.
  */
class HttpDownloadManagementActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("HttpDownloadManagementActorSpec"))

  import HttpDownloadManagementActorSpec._

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A HttpDownloadManagementActor" should "create correct Props" in {
    val config = mock[HttpArchiveConfig]
    val pathGen = mock[TempPathGenerator]
    val monitoringActor = TestProbe()
    val removeActor = TestProbe()

    val props = HttpDownloadManagementActor(config, pathGen, monitoringActor.ref,
      removeActor.ref)
    classOf[HttpDownloadManagementActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[HttpFlowFactory].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[HttpRequestSupport[DownloadOperationRequest]].isAssignableFrom(
      props.actorClass()) shouldBe true
    props.args should be(List(config, pathGen, monitoringActor.ref, removeActor.ref))
  }

  it should "execute a download request successfully" in {
    val request = MediumFileRequest(MediaFileID(TestMedium, DownloadUri), withMetaData = true)
    val response = HttpResponse()
    val helper = new DownloadManagementTestHelper

    helper.executeRequest(request, response)
    val downloadResponse = expectMsgType[MediumFileResponse]
    val (_, timeoutData) = helper.expectDownloadActorCreation()
    downloadResponse.request should be(request)
    downloadResponse.contentReader.get should be(timeoutData.child.ref)
    downloadResponse.length should be(0)
  }

  /**
    * Sends a test request to the test actor and obtains the transformation
    * function used by the download actor.
    *
    * @param request the request to be sent
    * @return the transformation function
    */
  private def sendRequestAndFetchTransformFunc(request: MediumFileRequest):
  DownloadTransformFunc = {
    val response = HttpResponse()
    val helper = new DownloadManagementTestHelper

    helper.executeRequest(request, response)
    expectMsgType[MediumFileResponse]
    val (fileData, _) = helper.expectDownloadActorCreation()
    fileData.props.args(2).asInstanceOf[DownloadTransformFunc]
  }

  it should "use an identity transformation if meta data should be downloaded" in {
    val request = MediumFileRequest(MediaFileID(TestMedium, DownloadUri), withMetaData = true)

    val transformFunc = sendRequestAndFetchTransformFunc(request)
    transformFunc should be(MediaFileDownloadActor.IdentityTransform)
  }

  it should "use a correct transformation function if meta data is to be stripped" in {
    val request = MediumFileRequest(MediaFileID(TestMedium, DownloadUri), withMetaData = false)

    val transformFunc = sendRequestAndFetchTransformFunc(request)
    transformFunc("Mp3") shouldBe a[ID3v2ProcessingStage]
  }

  it should "register the download actor at the monitoring actor" in {
    val request = MediumFileRequest(MediaFileID(TestMedium, DownloadUri), withMetaData = true)
    val helper = new DownloadManagementTestHelper

    helper.executeRequest(request, HttpResponse())
    expectMsgType[MediumFileResponse]
    val (_, timeoutData) = helper.expectDownloadActorCreation()
    helper.expectMonitoringRegistration(timeoutData.child.ref)
  }

  it should "increment the download index per operation" in {
    val request1 = MediumFileRequest(MediaFileID(TestMedium, DownloadUri), withMetaData = true)
    val request2 = MediumFileRequest(MediaFileID(TestMedium, DownloadUri), withMetaData = false)
    val response = HttpResponse()
    val helper = new DownloadManagementTestHelper

    def checkDownloadIndex(request: MediumFileRequest, expIdx: Int) {
      helper.executeRequest(request, response)
      expectMsgType[MediumFileResponse]
      val (_, timeoutData) = helper.expectDownloadActorCreation()
      timeoutData.props.args(5) should be(expIdx)
    }

    checkDownloadIndex(request1, 1)
    checkDownloadIndex(request2, 2)
  }

  it should "create an HTTP flow on demand" in {
    val helper = new DownloadManagementTestHelper

    helper.numberOfFlowsCreated should be(0)
  }

  it should "create only a single HTTP flow" in {
    val request1 = MediumFileRequest(MediaFileID(TestMedium, DownloadUri), withMetaData = true)
    val request2 = MediumFileRequest(MediaFileID(TestMedium, DownloadUri), withMetaData = false)
    val response = HttpResponse()
    val helper = new DownloadManagementTestHelper

    helper.executeRequest(request1, response)
    expectMsgType[MediumFileResponse]
    helper.executeRequest(request2, response)
    expectMsgType[MediumFileResponse]
    helper.numberOfFlowsCreated should be(1)
  }

  it should "handle a failed response from the HTTP archive" in {
    val request = MediumFileRequest(MediaFileID(TestMedium, DownloadUri), withMetaData = true)
    val helper = new DownloadManagementTestHelper

    helper.executeFailedRequest(request)
      .expectErrorResponse(request)
  }

  it should "send a failure response for an invalid URI" in {
    val request = MediumFileRequest(MediaFileID(TestMedium, "test://an invalid URI?!"),
      withMetaData = true)
    val helper = new DownloadManagementTestHelper

    helper.executeRequest(request, HttpResponse())
      .expectErrorResponse(request)
  }

  /**
    * A test helper class managing a test actor and its dependencies.
    */
  private class DownloadManagementTestHelper {
    /** The configuration for the HTTP archive. */
    private val config = createConfig()

    /** The generator for temporary paths. */
    private val pathGenerator = mock[TempPathGenerator]

    /** Test probe for the download monitoring actor. */
    private val probeMonitoringActor = TestProbe()

    /** Test probe for the remove files actor. */
    private val probeRemoveActor = TestProbe()

    /** A mock for the HTTP flow returned by the factory implementation. */
    private val httpFlow = createMockHttpFlow()

    /** Counter for the number of flow instances that have been created. */
    private val flowCreationCount = new AtomicInteger

    /** A queue for storing information about child actors. */
    private val childCreationQueue = new LinkedBlockingQueue[ChildCreationData]

    /** The test actor instance. */
    private val downloadManager = createTestActor()

    /** A request for a medium file. */
    private var expectedRequest: MediumFileRequest = _

    /**
      * An option with the response of a file request from the HTTP archive.
      * This is used to simulate the request processing. If no response is
      * specified, the request will fail.
      */
    private var optArchiveResponse: Option[HttpResponse] = None

    /**
      * Sends a request to download a file to the test actor and initializes
      * the response to be returned from the archive.
      *
      * @param request  the request for the file to be downloaded
      * @param response the response to be returned by the archive
      * @return this test helper
      */
    def executeRequest(request: MediumFileRequest, response: HttpResponse):
    DownloadManagementTestHelper = {
      expectedRequest = request
      optArchiveResponse = Option(response)
      downloadManager ! request
      this
    }

    /**
      * Executes a download request that is going to fail. The HTTP sender
      * implementation is prepared to return a failed future.
      *
      * @param request the request for the file to be downloaded
      * @return this test helper
      */
    def executeFailedRequest(request: MediumFileRequest): DownloadManagementTestHelper =
      executeRequest(request, null)

    /**
      * Check that correct child actors for a download operation have been
      * created. The data objects for the creation of the file download actor
      * and the timeout download actor are returned, so that further checks can
      * be performed.
      *
      * @return a tuple with creation data for both child actors
      */
    def expectDownloadActorCreation(): (ChildCreationData, ChildCreationData) = {
      val fileDownloadCreation = nextChildActorCreation()
      fileDownloadCreation.props.actorClass() should be(classOf[HttpFileDownloadActor])
      fileDownloadCreation.props.args.head should be(optArchiveResponse.get)

      val timeoutActorCreation = nextChildActorCreation()
      classOf[TimeoutAwareHttpDownloadActor].isAssignableFrom(
        timeoutActorCreation.props.actorClass()) shouldBe true
      classOf[ChildActorFactory].isAssignableFrom(
        timeoutActorCreation.props.actorClass()) shouldBe true
      timeoutActorCreation.props.args.head should be(config)
      timeoutActorCreation.props.args(1) should be(probeMonitoringActor.ref)
      timeoutActorCreation.props.args(2) should be(fileDownloadCreation.child.ref)
      timeoutActorCreation.props.args(3) should be(pathGenerator)
      timeoutActorCreation.props.args(4) should be(probeRemoveActor.ref)

      (fileDownloadCreation, timeoutActorCreation)
    }

    /**
      * Checks that no child actor has been created.
      *
      * @return this test helper
      */
    def expectNoChildActorCreation(): DownloadManagementTestHelper = {
      childCreationQueue shouldBe 'empty
      this
    }

    /**
      * Returns the number of flow instances created by the test actor.
      *
      * @return the number of flow instances created
      */
    def numberOfFlowsCreated: Int = flowCreationCount.get()

    /**
      * Expects that a download actor was registered at the monitoring actor.
      *
      * @param actor the actor to be registered
      * @return this test helper
      */
    def expectMonitoringRegistration(actor: ActorRef): DownloadManagementTestHelper = {
      probeMonitoringActor.expectMsg(DownloadOperationStarted(actor, testActor))
      this
    }

    /**
      * Expects that a ''MediumFileResponse'' was sent to the test actor
      * indicating a failure.
      *
      * @param request the original request
      * @return this test helper
      */
    def expectErrorResponse(request: MediumFileRequest): DownloadManagementTestHelper = {
      val response = expectMsgType[MediumFileResponse]
      response.request should be(request)
      response.contentReader should be(None)
      response.length should be(-1)
      expectNoChildActorCreation()
    }

    /**
      * Returns information about the next child actor that has been created
      * by the test instance. If no child is created in a specific time frame,
      * this method fails.
      *
      * @return a data object for the next child actor creation
      */
    private def nextChildActorCreation(): ChildCreationData = {
      val data = childCreationQueue.poll(3, TimeUnit.SECONDS)
      data should not be null
      data
    }

    /**
      * Creates a mock configuration for the HTTP archive.
      *
      * @return the configuration
      */
    private def createConfig(): HttpArchiveConfig = {
      val config = mock[HttpArchiveConfig]
      when(config.archiveURI).thenReturn(Uri(ArchiveUri))
      when(config.credentials).thenReturn(Credentials)
      config
    }

    /**
      * Creates an HTTP flow object. What this flow does, is irrelevant. The
      * object must only be defined, so that it can be checked against when
      * sending a request.
      *
      * @return the flow object
      */
    private def createMockHttpFlow(): Flow[(HttpRequest, DownloadOperationRequest),
      (Try[HttpResponse], DownloadOperationRequest), Any] =
      Flow.fromFunction[(HttpRequest, DownloadOperationRequest),
        (Try[HttpResponse], DownloadOperationRequest)] { req =>
        (Try(HttpResponse()), req._2)
      }

    /**
      * Creates a new test actor instance.
      *
      * @return the test actor
      */
    private def createTestActor(): ActorRef =
      system.actorOf(Props(new HttpDownloadManagementActor(config, pathGenerator,
        probeMonitoringActor.ref, probeRemoveActor.ref) with ChildActorFactory
        with HttpFlowFactory with SystemPropertyAccess
        with HttpRequestSupport[DownloadOperationRequest] {
        /**
          * @inheritdoc This implementation does some parameter checks and
          *             returns the mock HTTP flow.
          */
        override def createHttpFlow[T](uri: Uri)(implicit mat: Materializer, sys: ActorSystem)
        : Flow[(HttpRequest, T), (Try[HttpResponse], T), Any] = {
          uri should be(Uri(ArchiveUri))
          sys should be(system)
          mat should not be null
          flowCreationCount.incrementAndGet()
          httpFlow.asInstanceOf[Flow[(HttpRequest, T), (Try[HttpResponse], T), Any]]
        }

        /**
          * @inheritdoc This implementation checks the request and returns the
          *             predefined response. If no response is specified, a
          *             failing ''Future'' is returned.
          */
        override def sendRequest(request: HttpRequest, data: DownloadOperationRequest, flow:
        Flow[(HttpRequest, DownloadOperationRequest), (Try[HttpResponse],
          DownloadOperationRequest), Any])(implicit mat: Materializer, ec: ExecutionContext):
        Future[(HttpResponse, DownloadOperationRequest)] = {
          mat should not be null
          request.method should be(HttpMethods.GET)
          request.headers should contain(Authorization(BasicHttpCredentials(Credentials.userName,
            Credentials.password)))
          data.request should be(expectedRequest)
          data.client should be(testActor)
          request.uri should be(Uri(data.request.fileID.uri))
          optArchiveResponse match {
            case Some(response) =>
              Future((response, data))
            case None =>
              Future.failed(new IOException("Error from HTTP archive!"))
          }
        }

        /**
          * @inheritdoc This implementation stores information about the child
          *             creation and returns a test probe.
          */
        override def createChildActor(p: Props): ActorRef = {
          val child = TestProbe()
          val creationData = ChildCreationData(child = child, props = p)
          childCreationQueue offer creationData
          creationData.child.ref
        }
      }))
  }

}