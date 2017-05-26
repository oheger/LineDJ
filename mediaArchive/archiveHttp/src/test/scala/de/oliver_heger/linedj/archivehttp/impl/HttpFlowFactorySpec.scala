/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.impl

import javax.net.ssl.SSLException
import javax.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Source}
import akka.testkit.TestKit
import akka.util.ByteString
import de.oliver_heger.linedj.archivecommon.stream.StreamSizeRestrictionStage
import org.eclipse.jetty.server.{AbstractNetworkConnector, Server}
import org.eclipse.jetty.servlet.ServletHandler
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object HttpFlowFactorySpec {
  /**
    * Path under which the test servlet returning the passed in path is
    * registered.
    */
  val ServletPath = "/test-request"

  /**
    * Path under which the test servlet producing content is registered.
    */
  val ServletContent = "/content"

  /** The servlet parameter for the number of repeats. */
  val ParamRepeats = "repeatCount"

  /** Test content generated by the content servlet. */
  val TestContent = "Was Du heute kannst besorgen, das verschiebe nicht auf morgen."

  /** A timeout for tests. */
  private val TimeoutValue = 5.seconds
}

/**
  * Test class for ''HttpFlowFactory''. The class also tests functionality
  * related to limiting the size of responses.
  */
class HttpFlowFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("HttpFlowFactorySpec"))

  import HttpFlowFactorySpec._
  import system.dispatcher

  /** Object to materialize streams. */
  private implicit val mat = ActorMaterializer()

  /** A reference to the embedded server. */
  private var server: Server = _

  /** The port under which the server is running. */
  private var localPort = 0

  override protected def beforeAll(): Unit = {
    server = new Server(0)
    val handler = new ServletHandler
    handler.addServletWithMapping(classOf[PathTestServlet], ServletPath + "/*")
    handler.addServletWithMapping(classOf[ContentTestServlet], ServletContent + "/*")
    server setHandler handler
    server.start()
    localPort = server.getConnectors.head.asInstanceOf[AbstractNetworkConnector].getLocalPort
  }

  override protected def afterAll(): Unit = {
    server.stop()
    server.join()
    val futureTerminate = Http().shutdownAllConnectionPools() andThen {
      case _ => TestKit shutdownActorSystem system
    }
    Await.ready(futureTerminate, TimeoutValue)
  }

  /**
    * Reads the entity of the given response and returns it as string.
    *
    * @param response the response to be read
    * @return the content read from the response
    */
  def readResponse(response: HttpResponse): String = {
    val respSrc = response.entity.dataBytes
    readResponseSource(respSrc)
  }

  /**
    * Reads the content of the given source and returns it as a string.
    *
    * @param respSrc the source to be read
    * @return the content read from the source as string
    */
  private def readResponseSource(respSrc: Source[ByteString, _]): String = {
    val futureByteStr = respSrc.runFold(ByteString())(_ ++ _)
    Await.result(futureByteStr, TimeoutValue).utf8String
  }

  /**
    * Sends a request to the specified URI and returns the text response.
    * Fails if no success response is returned
    *
    * @param flow the HTTP flow
    * @param uri  the URI for the request
    * @return the response text from the server
    */
  def processRequest(flow: Flow[(HttpRequest, String), (Try[HttpResponse], String), _],
                     uri: String): String = {
    sendRequest(flow, uri) match {
      case Success(response) if response.status.isSuccess() =>
        readResponse(response)
      case Success(response) if !response.status.isSuccess() =>
        Await.ready(response.discardEntityBytes().future(), TimeoutValue)
        fail("No success response: " + response)
      case r =>
        fail("Unexpected response: " + r)
    }
  }

  /**
    * Sends a request to the specified URI and returns the response.
    *
    * @param flow the HTTP flow
    * @param uri  the URI for the request
    * @return a ''Try'' with the received response
    */
  private def sendRequest(flow: Flow[(HttpRequest, String), (Try[HttpResponse], String), _],
                          uri: String): Try[HttpResponse] = {
    val token = System.nanoTime().toString
    val request = HttpRequest(uri = uri)
    val futureStream = Source.single((request, token))
      .via(flow)
      .runFold(List.empty[Try[HttpResponse]])((lst, e) => {
        e._2 should be(token)
        e._1 :: lst
      })
    Await.result(futureStream, TimeoutValue).head
  }

  /**
    * Creates a test HTTP flow factory.
    *
    * @return the test instance
    */
  private def createHttpFlowFactory(): HttpFlowFactory =
    new HttpFlowFactory {}

  "A HttpFlowFactory" should "create a flow through which a request can be sent" in {
    val factory = createHttpFlowFactory()
    val serverUri = Uri.from(scheme = "http", host = "localhost", port = localPort)
    val flow = factory.createHttpFlow[String](serverUri)
    val Path = "/my/test/path/data.tst"

    val response = processRequest(flow, ServletPath + Path)
    response should be(Path)
  }

  it should "support multiple requests" in {
    val factory = createHttpFlowFactory()
    val serverUri = Uri.from(scheme = "http", host = "localhost", port = localPort)
    val flow = factory.createHttpFlow[String](serverUri)

    (1 to 16).map("/path" + _) foreach { p =>
      processRequest(flow, ServletPath + p) should be(p)
    }
  }

  it should "extract the port from a URI" in {
    val factory = createHttpFlowFactory()
    val serverUri = Uri.from(scheme = "http", host = "localhost", port = localPort)

    factory.extractPort(serverUri, localPort + 1) should be(localPort)
  }

  it should "return a default port for URIs without a port" in {
    val factory = createHttpFlowFactory()
    val uri = Uri("https://test.org")
    val DefaultPort = 8888

    factory.extractPort(uri, DefaultPort) should be(DefaultPort)
  }

  it should "behave differently for HTTPS" in {
    val factory = createHttpFlowFactory()
    val serverUri = Uri.from(scheme = "https", host = "localhost", port = localPort)
    val flow = factory.createHttpFlow[String](serverUri)

    val response = sendRequest(flow, ServletPath + "somePath")
    response match {
      case Failure(e) =>
        e shouldBe a[SSLException]
      case r =>
        fail("Unexpected response: " + r)
    }
  }

  "A client flow" should "support response size restriction" in {
    val factory = createHttpFlowFactory()
    val serverUri = Uri.from(scheme = "http", host = "localhost", port = localPort)
    val flow = factory.createHttpFlow[String](serverUri)
    val sizeStage = new StreamSizeRestrictionStage(2 * TestContent.length + 8)

    @tailrec def requestContent(results: List[Try[String]], i: Int): List[Try[String]] =
      if (i <= 0) results
      else {
        val contentSize = if (i % 2 != 0) 2 else 256
        val response = sendRequest(flow, s"$ServletContent?$ParamRepeats=$contentSize")
        val content = response.flatMap { resp =>
          Try {
            readResponseSource(resp.entity.dataBytes.via(sizeStage))
          }
        }
        requestContent(content :: results, i - 1)
      }

    val results = requestContent(List.empty, 64)
    results.zipWithIndex foreach { t =>
      t._1 match {
        case Success(s) =>
          t._2 % 2 should be(0)
          s should be(TestContent * 2)
        case Failure(e) =>
          t._2 % 2 should not be 0
          e shouldBe a[IllegalStateException]
      }
    }
  }
}

/**
  * A test servlet to be called by test requests. The servlet returns a text
  * response with the path it was invoked with.
  */
class PathTestServlet extends HttpServlet {
  override def doGet(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    resp.setContentType("text/plain")
    resp.setStatus(HttpServletResponse.SC_OK)
    resp.getWriter.print(req.getPathInfo)
  }
}

/**
  * A test servlet which produces a response with some repeated content. This
  * is used to test whether huge responses can be canceled. The servlet
  * supports GET calls and expects a parameter for the number of content lines
  * to be produced. Then the test string is written for the specified number of
  * times into the response stream.
  */
class ContentTestServlet extends HttpServlet {
  override def doGet(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    val count = req.getParameter(HttpFlowFactorySpec.ParamRepeats).toInt
    resp.setContentType("text/plain")
    resp.setStatus(HttpServletResponse.SC_OK)
    val writer = resp.getWriter

    @tailrec def writeContent(i: Int): Unit = {
      if (i < count) {
        writer.print(HttpFlowFactorySpec.TestContent)
        writeContent(i + 1)
      }
    }

    writeContent(0)
  }
}
