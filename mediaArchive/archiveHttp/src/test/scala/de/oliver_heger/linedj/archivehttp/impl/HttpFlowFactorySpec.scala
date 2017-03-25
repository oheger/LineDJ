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
import org.eclipse.jetty.server.{AbstractNetworkConnector, Server}
import org.eclipse.jetty.servlet.ServletHandler
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object HttpFlowFactorySpec {
  /** Path under which the test servlet is registered. */
  val ServletPath = "/test-request"

  /** A timeout for tests. */
  private val TimeoutValue = 5.seconds
}

/**
  * Test class for ''HttpFlowFactory''.
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
    val futureByteStr = response.entity.dataBytes.runFold(ByteString())(_ ++ _)
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

  "A HttpFlowFactory" should "create a flow through which a request can be sent" in {
    val factory = new HttpFlowFactory {}
    val serverUri = Uri.from(scheme = "http", host = "localhost", port = localPort)
    val flow = factory.createHttpFlow[String](serverUri)
    val Path = "/my/test/path/data.tst"

    val response = processRequest(flow, ServletPath + Path)
    response should be(Path)
  }

  it should "support multiple requests" in {
    val factory = new HttpFlowFactory {}
    val serverUri = Uri.from(scheme = "http", host = "localhost", port = localPort)
    val flow = factory.createHttpFlow[String](serverUri)

    (1 to 16).map("/path" + _) foreach { p =>
      processRequest(flow, ServletPath + p) should be(p)
    }
  }

  it should "behave differently for HTTPS" in {
    val factory = new HttpFlowFactory {}
    val serverUri = Uri.from(scheme = "https", host = "localhost", port = localPort)
    val flow = factory.createHttpFlow[String](serverUri)

    val response = sendRequest(flow, "somePath")
    response match {
      case Failure(e) =>
        e shouldBe a[SSLException]
      case r =>
        fail("Unexpected response: " + r)
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