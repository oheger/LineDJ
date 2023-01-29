/*
 * Copyright 2015-2023 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine.radio.stream

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.StandardRoute
import akka.stream.scaladsl.Sink
import akka.testkit.TestKit
import akka.util.ByteString
import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.io.IOException
import java.net.ServerSocket
import scala.concurrent.Future
import scala.util.Using

object HttpStreamLoaderSpec {
  /** Constant for the host of the test server. */
  private val Host = "localhost"

  /**
    * Determines a free port number that can be used by the test server.
    *
    * @return the free port number
    */
  private def findFreePort(): Int =
    Using(new ServerSocket(0)) {
      _.getLocalPort
    }.get
}

/**
  * Test class for [[HttpStreamLoader]].
  */
class HttpStreamLoaderSpec(tesSystem: ActorSystem) extends TestKit(tesSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with AsyncTestHelper {
  def this() = this(ActorSystem("HttpStreamLoaderSpec"))

  import HttpStreamLoaderSpec._

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  /**
    * Runs a test that needs a mock server. A server is started at a random
    * port and configured with the given routing function. Then this function
    * executes the test block, passing in the root URL to the server.
    *
    * @param route the routing function
    * @param block the test block to execute
    */
  private def runWithServer(route: HttpRequest => Future[HttpResponse])(block: String => Unit): Unit = {
    val port = findFreePort()
    val binding = futureResult(Http().newServerAt(Host, port).bind(route))

    try {
      block(s"http://$Host:$port")
    } finally {
      futureResult(binding.unbind())
    }
  }

  /**
    * Completes the current request with a response containing the test data
    * text.
    *
    * @return the route to complete the request with test data
    */
  private def completeTestData(): StandardRoute =
    complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, FileTestHelper.TestData))

  /**
    * Extracts the body of the given response and also checks whether the
    * response status is successful.
    *
    * @param futResponse a ''Future'' with a response
    * @return the response body
    */
  private def responseBody(futResponse: Future[HttpResponse]): String = {
    val response = futureResult(futResponse)
    response.status.isSuccess() shouldBe true

    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    futureResult(response.entity.dataBytes.runWith(sink)).utf8String
  }

  "HttpStreamLoader" should "send a request" in {
    val route = get {
      completeTestData()
    }

    val loader = new HttpStreamLoader
    runWithServer(route) { url =>
      val request = HttpRequest(uri = url)
      val futResponse = loader.sendRequest(request)

      responseBody(futResponse) should be(FileTestHelper.TestData)
    }
  }

  it should "check the status of the response" in {
    val ErrorPath = "error"
    val route = get {
      path(ErrorPath) {
        complete(StatusCodes.BadRequest)
      }
    }

    val loader = new HttpStreamLoader
    runWithServer(route) { uri =>
      val request = HttpRequest(uri = s"$uri/$ErrorPath")
      val futResponse = loader.sendRequest(request)

      val exception = expectFailedFuture[IOException](futResponse)
      exception.getMessage should include(StatusCodes.BadRequest.intValue.toString)
      exception.getMessage should include(StatusCodes.BadRequest.reason)
    }
  }

  /**
    * Tests whether redirect responses with a given status code are handled
    * correctly.
    *
    * @param status the status code
    */
  private def checkRedirect(status: StatusCodes.Redirection): Unit = {
    val RedirectPath = "redirect"
    val TargetPath = "result"
    val TestHeaderName = "X-Test-Header"
    val TestHeaderValue = "Found the header"

    val route = get {
      concat(
        path(RedirectPath) {
          redirect(s"/$TargetPath", status)
        },
        path(TargetPath) {
          headerValueByName(TestHeaderName) { value =>
            complete(value)
          }
        }
      )
    }

    val loader = new HttpStreamLoader
    runWithServer(route) { uri =>
      val request = HttpRequest(uri = s"$uri/$RedirectPath",
        headers = Seq(RawHeader(TestHeaderName, TestHeaderValue)))
      val futResponse = loader.sendRequest(request)

      responseBody(futResponse) should be(TestHeaderValue)
    }
  }

  it should "handle a temporary redirect response" in {
    checkRedirect(StatusCodes.TemporaryRedirect)
  }

  it should "handle a permanent redirect response" in {
    checkRedirect(StatusCodes.PermanentRedirect)
  }

  it should "handle a redirect response without a Location header" in {
    val route = get {
      complete(StatusCodes.TemporaryRedirect, "Strange response ;-)")
    }

    val loader = new HttpStreamLoader
    runWithServer(route) { uri =>
      val request = HttpRequest(uri = uri)

      expectFailedFuture[IOException](loader.sendRequest(request))
    }
  }

  it should "handle the scheme in a redirect URI" in {
    val requestUri = Uri("https://www.example.org:8080/request")
    val location = Uri("/redirect/path")
    val expectedRedirectUri = Uri("https://www.example.org:8080/redirect/path")

    HttpStreamLoader.constructRedirectUri(requestUri, location) should be(expectedRedirectUri)
  }

  it should "handle absolute redirect URIs" in {
    val requestUri = Uri("http://localhost:1234/local/test")
    val location = Uri("https://www.example.org/redirect/file.html")

    HttpStreamLoader.constructRedirectUri(requestUri, location) should be(location)
  }

  it should "handle an infinite redirect loop" in {
    val RedirectPath = "redirect"
    val route = get {
      path(RedirectPath) {
        redirect(s"/$RedirectPath", StatusCodes.TemporaryRedirect)
      }
    }

    val loader = new HttpStreamLoader
    runWithServer(route) { uri =>
      val request = HttpRequest(uri = s"$uri/$RedirectPath")

      expectFailedFuture[IOException](loader.sendRequest(request))
    }
  }
}
