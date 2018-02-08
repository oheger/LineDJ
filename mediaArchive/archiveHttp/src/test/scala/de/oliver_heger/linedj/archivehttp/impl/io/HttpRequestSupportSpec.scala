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

package de.oliver_heger.linedj.archivehttp.impl.io

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Source}
import akka.testkit.TestKit
import akka.util.ByteString
import org.mockito.Mockito._
import org.mockito.Matchers.any
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.Try

object HttpRequestSupportSpec {
  /** A test request expected by the test HTTP flow. */
  private val TestRequest = HttpRequest(uri = Uri("http://test.request.org"))

  /** Test data which is passed through the flow. */
  private val TestData = "Test data object"
}

/**
  * Test class for ''HttpRequestSupport''.
  */
class HttpRequestSupportSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("HttpRequestSupportSpec"))

  import HttpRequestSupportSpec._

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Executes a request via a test instance. A flow is used which produces the
    * specified response.
    *
    * @param response the response to be returned
    * @return the response ''Future'' returned by the test instance
    */
  private def checkRequestExecution(response: Try[HttpResponse]):
  Future[(HttpResponse, String)] = {
    implicit val mat: ActorMaterializer = ActorMaterializer()
    implicit val ec: ExecutionContextExecutor = system.dispatcher
    val sender = new HttpRequestSupport[String] {}
    sender.sendRequest(TestRequest, TestData, createTestHttpFlow(response))
  }

  /**
    * Creates the test HTTP flow. Here a simulated flow is returned which
    * maps the expected test request to a specific response.
    *
    * @param triedResponse the response to be returned
    * @return the test HTTP flow
    */
  private def createTestHttpFlow(triedResponse: Try[HttpResponse]):
  Flow[(HttpRequest, String), (Try[HttpResponse], String), NotUsed] = {
    Flow.fromFunction[(HttpRequest, String), (Try[HttpResponse], String)] { req =>
      val resp = if (req._1 == TestRequest) triedResponse
      else Try[HttpResponse](throw new IllegalArgumentException("Unexpected request!"))
      (resp, req._2)
    }
  }

  /**
    * Checks the execution of a request which is expected to return a failed
    * future.
    *
    * @param response the response to be returned by the test flow
    * @return the exception wrapped by the future
    */
  private def checkFailedRequestExecution(response: Try[HttpResponse]): Throwable = {
    val futResponse = checkRequestExecution(response)
    intercept[Throwable] {
      Await.result(futResponse, 3.seconds)
    }
  }

  "An HttpRequestSupport" should "return a response if processing is successful" in {
    val response = HttpResponse()

    val futResult = checkRequestExecution(Try(response))
    val (result, data) = Await.result(futResult, 3.seconds)
    result should be theSameInstanceAs response
    data should be(TestData)
  }

  it should "return a failed future if there is a processing error" in {
    val exception = new Exception("Boom")
    val response = Try[HttpResponse] {
      throw exception
    }

    checkFailedRequestExecution(response) should be(exception)
  }

  it should "return a failed future for a non-success response" in {
    val response = HttpResponse(status = StatusCodes.BadRequest)

    checkFailedRequestExecution(Try(response)) should be(FailedRequestException(response))
  }

  it should "discard the body of a failed response" in {
    val content = ByteString("This is the body of the test response.")
    val entity = mock[ResponseEntity]
    when(entity.dataBytes).thenReturn(Source.single(content))
    val response = HttpResponse(status = StatusCodes.Unauthorized, entity = entity)
    checkFailedRequestExecution(Try(response))

    verify(entity).discardBytes(any())
  }
}
