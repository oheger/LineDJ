/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.impl.io

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue

import akka.Done
import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source, SourceQueueWithComplete}
import akka.testkit.{TestActorRef, TestKit}
import akka.util.{ByteString, Timeout}
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.archivehttp.http.HttpRequests
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

object HttpRequestActorSpec {
  /** A timeout for request execution. */
  private implicit val RequestTimeout: Timeout = Timeout(3.seconds)

  /** The URI to the test server. */
  private val ServerUri = Uri("https://my.server.biz")

  /** Path for a test request. */
  private val RequestPath = "/foo"

  /** A data object to be passed to a request. */
  private val Data = new Object

  /** The size of the request queue. */
  private val QueueSize = 42

  /** A test configuration for an HTTP archive. */
  private val TestConfig = HttpArchiveConfig(archiveURI = ServerUri,
    requestQueueSize = QueueSize, archiveName = "MyTestArchive", processorCount = 1,
    processorTimeout = Timeout(1.minute), propagationBufSize = 128, maxContentSize = 10000,
    downloadBufferSize = 65536, downloadMaxInactivity = 30.seconds, downloadReadChunkSize = 100,
    timeoutReadSize = 1024, downloadConfig = null, metaMappingConfig = null, contentMappingConfig = null,
    cryptUriCacheSize = 1024, needsCookieManagement = false, protocol = null, authFunc = null)

  /** A list with default headers of the test request. */
  private val DefaultHeaders = List(ETag("some_tag"))

  /** A test request. */
  private val TestRequest = HttpRequest(uri = RequestPath, headers = DefaultHeaders)

  /** Test message to send the test request. */
  private val TestSendRequest = HttpRequests.SendRequest(TestRequest, Data)

  /**
    * A case class storing data about an expected request.
    *
    * @param request  the request
    * @param response the tried response to be returned
    */
  private case class ExpectedRequest(request: HttpRequest, response: Try[HttpResponse])

}

/**
  * Test class for ''HttpRequestActor''.
  */
class HttpRequestActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("HttpRequestActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import HttpRequestActorSpec._

  "A HttpRequestActor" should "create correct properties from a configuration" in {
    val props = HttpRequestActor(TestConfig)

    classOf[HttpRequestActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[HttpFlowFactory].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(TestConfig.archiveURI, TestConfig.requestQueueSize))
  }

  it should "create correct properties from single parameters" in {
    val uri = TestConfig.archiveURI
    val queueSize = TestConfig.requestQueueSize

    val props = HttpRequestActor(uri, queueSize)
    classOf[HttpRequestActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[HttpFlowFactory].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(TestConfig.archiveURI, TestConfig.requestQueueSize))
  }

  it should "handle a successful request" in {
    val expResponse = HttpResponse()
    val helper = new RequestActorTestHelper

    val response = helper.expectRequest(TestRequest, expResponse)
      .sendRequest()
    response should be(expResponse)
  }

  it should "handle a request that causes an exception" in {
    val exception = new IOException("Failed request")
    val helper = new RequestActorTestHelper

    val responseException = helper.expectFailedRequest(TestRequest, exception)
      .sendRequestAndExpectFailure[FailedRequestException]
    responseException.cause should be(exception)
    responseException.response shouldBe 'empty
    responseException.request should be(TestSendRequest)
  }

  it should "fail the response future for a non-success response" in {
    val response = HttpResponse(status = StatusCodes.BadRequest)
    val helper = new RequestActorTestHelper

    val exception = helper.expectRequest(TestRequest, response)
      .sendRequestAndExpectFailure[FailedRequestException]
    exception.response should be(Some(response))
    exception.request should be(TestSendRequest)
    exception.cause should be(null)
  }

  it should "discard the entity bytes of a non-success response" in {
    val content = ByteString("This is the body of the test response.")
    val entity = mock[ResponseEntity]
    val discardedEntity = new HttpMessage.DiscardedEntity(Future.successful(Done))
    when(entity.dataBytes).thenReturn(Source.single(content))
    when(entity.discardBytes()(any(classOf[Materializer]))).thenReturn(discardedEntity)
    val response = HttpResponse(status = StatusCodes.Unauthorized, entity = entity)
    val helper = new RequestActorTestHelper

    val exception = helper.expectRequest(TestRequest, response)
      .sendRequestAndExpectFailure[FailedRequestException]
    verify(entity).discardBytes()(any())
    exception.request should be(TestSendRequest)
  }

  it should "close the request queue when it is stopped" in {
    val queue = mock[SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])]]
    val requestActor = system.actorOf(Props(new HttpRequestActor(TestConfig.archiveURI, TestConfig.requestQueueSize)
      with HttpFlowFactory with SystemPropertyAccess {
      override def requestQueue: SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])] = queue
    }))

    system stop requestActor
    watch(requestActor)
    expectTerminated(requestActor)
    verify(queue).complete()
  }

  /**
    * A test helper class that manages a test actor instance and some helper
    * objects.
    */
  private class RequestActorTestHelper {
    /** A queue that stores expected requests and their responses. */
    private val expectedRequestQueue = new LinkedBlockingQueue[(HttpRequest, Try[HttpResponse])]

    /** Reference to the actor to be tested. */
    private val requestActor = createTestActor()

    /**
      * Adds a request/response pair to the queue of expected requests. When a
      * request is received it is checked against the next request in the
      * queue. If successful, the given response is returned.
      *
      * @param request  the expected request
      * @param response the response to be returned
      * @return this test helper
      */
    def expectRequest(request: HttpRequest, response: HttpResponse): RequestActorTestHelper = {
      expectedRequestQueue offer(request, Success(response))
      this
    }

    /**
      * Adds a request to the queue of expected requests that should trigger an
      * exception. If the given request is received, the exception will be
      * returned.
      *
      * @param request the expected request
      * @param failure the exception to answer this request
      * @return this test helper
      */
    def expectFailedRequest(request: HttpRequest, failure: Throwable): RequestActorTestHelper = {
      expectedRequestQueue offer(request, Failure(failure))
      this
    }

    /**
      * Sends a test request to the test actor, expects a successful
      * response and returns it.
      *
      * @param request the test request to be sent
      * @return the response from the test actor
      */
    def sendRequest(request: HttpRequests.SendRequest = TestSendRequest): HttpResponse = {
      val futResponse = HttpRequests.sendRequest(requestActor, request)
      val responseData = Await.result(futResponse, RequestTimeout.duration)
      responseData.data should be(Data)
      responseData.response
    }

    /**
      * Sends the test request to the test actor and expects an exception to be
      * thrown. The exception is returned.
      *
      * @param t the class tag
      * @tparam E the type of the expected exception
      * @return the exception
      */
    def sendRequestAndExpectFailure[E <: AnyRef](implicit t: ClassTag[E]): E = intercept[E] {
      sendRequest()
    }

    /**
      * Creates a reference to the actor to be tested.
      *
      * @return the test actor
      */
    private def createTestActor(): TestActorRef[HttpRequestActor] =
      TestActorRef(createTestActorProps())

    /**
      * Creates properties to create a test actor instance. The actor uses a
      * mock HTTP flow that returns predefined responses.
      *
      * @return the ''Props'' for a test actor instance
      */
    private def createTestActorProps(): Props =
      Props(new HttpRequestActor(TestConfig.archiveURI, TestConfig.requestQueueSize) with HttpFlowFactory
        with SystemPropertyAccess {
        override def createHttpFlow[T](uri: Uri)(implicit mat: Materializer, system: ActorSystem):
        Flow[(HttpRequest, T), (Try[HttpResponse], T), Any] = {
          uri should be(TestConfig.archiveURI)
          Flow[(HttpRequest, T)] map { t =>
            val (req, triedResp) = expectedRequestQueue.poll()
            t._1 should be(req)
            (triedResp, t._2)
          }
        }
      })
  }

}
