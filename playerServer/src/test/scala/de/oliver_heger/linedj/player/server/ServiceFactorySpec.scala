/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.player.server

import akka.Done
import akka.actor.{ActorSystem, Terminated}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, StatusCodes}
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.testkit.TestKit
import akka.util.ByteString
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.utils.ActorManagement
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.{AnyFlatSpec, AnyFlatSpecLike}
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.net.ServerSocket
import java.nio.file.Paths
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import scala.util.Using

object ServiceFactorySpec:
  /** The timeout when waiting for a future. */
  private val FutureTimeout = 3.seconds

  /**
    * Waits for the given [[Future]] to be completed and returns its result or
    * throws an exception if the future failed or does not complete within the
    * timeout.
    *
    * @param future the [[Future]]
    * @tparam T the result type of the future
    * @return the completed value of the future
    */
  private def futureResult[T](future: Future[T]): T =
    Await.result(future, FutureTimeout)

  /**
    * Returns a free port that can be used for tests with a server instance.
    *
    * @return the port number
    */
  private def freePort(): Int =
    Using(new ServerSocket(0)) { socket =>
      socket.getLocalPort
    }.get

  /**
    * Reads the content of the given source into a string.
    *
    * @param source the source
    * @param mat    the object to materialize streams
    * @tparam M the materialized type of the source
    * @return the string content of the source
    */
  private def readSource[M](source: Source[ByteString, M])(implicit mat: Materializer): String =
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    futureResult(source.runWith(sink)).utf8String

end ServiceFactorySpec

/**
  * Test class for [[ServiceFactory]].
  */
class ServiceFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(ActorSystem("ServiceFactorySpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  import ServiceFactorySpec.*

  /**
    * Returns a [[PlayerServerConfig]] that can be used to start an HTTP
    * server. It is initialized with an unused server port and the path to the
    * folder containing the test UI.
    *
    * @return the configuration
    */
  private def httpServerConfig(): PlayerServerConfig =
    ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system))
      .copy(serverPort = freePort(),
        uiContentFolder = Paths.get("playerServer", "src", "test", "resources", "ui"),
        uiPath = "/ui/index.html")

  /**
    * Starts the HTTP server using a test factory instance and the provided
    * parameters. Then, the given test block is executed. Finally, the server
    * is shut down again.
    *
    * @param config          the server configuration
    * @param radioPlayer     the radio player
    * @param shutdownPromise the promise to trigger shutdown
    * @param block           the test block to execute
    */
  private def runHttpServerTest(config: PlayerServerConfig = httpServerConfig(),
                                radioPlayer: RadioPlayer = mock,
                                shutdownPromise: Promise[Done] = Promise())
                               (block: PlayerServerConfig => Unit): Unit =
    val factory = new ServiceFactory
    val bindings = futureResult(factory.createHttpServer(config, mock, shutdownPromise))

    try
      block(config)
    finally
      futureResult(bindings.unbind())

  "createRadioPlayer" should "correctly create and initialize a radio player" in {
    val creator = ServerConfigTestHelper.actorCreator(system)
    val config = ServerConfigTestHelper.defaultServerConfig(creator)

    val factory = new ServiceFactory
    val player = futureResult(factory.createRadioPlayer(config))

    player.config should be(config.radioPlayerConfig)
    creator.actorManagement.managedActorNames.size should be > 0

    creator.actorManagement.stopActors()
  }

  "createHttpServer" should "start the HTTP server with the UI route" in {
    runHttpServerTest() { config =>
      val uiRequest = HttpRequest(uri = s"http://localhost:${config.serverPort}${config.uiPath}")
      val response = futureResult(Http().singleRequest(uiRequest))
      response.status should be(StatusCodes.OK)

      val expectedString = readSource(FileIO.fromPath(config.uiContentFolder.resolve("index.html")))
      val responseString = readSource(response.entity.dataBytes)
      responseString should be(expectedString)
    }
  }

  it should "set up a route to trigger the server shutdown" in {
    val shutdownPromise = Promise[Done]()

    runHttpServerTest(shutdownPromise = shutdownPromise) { config =>
      val shutdownRequest = HttpRequest(uri = s"http://localhost:${config.serverPort}/api/shutdown",
        method = HttpMethods.POST)
      val shutdownResponse = futureResult(Http().singleRequest(shutdownRequest))
      shutdownResponse.status should be(StatusCodes.Accepted)
      shutdownPromise.isCompleted shouldBe true
      futureResult(shutdownPromise.future) should be(Done)
    }
  }

  "enableGracefulShutdown" should "call the shutdown when all conditions are met" in {
    val mockBinding = mock[ServerBinding]
    val mockSystem = mock[ActorSystem]
    val mockManagement = mock[ActorManagement]
    when(mockSystem.dispatcher).thenReturn(system.dispatcher)
    when(mockSystem.terminate()).thenReturn(Future.successful(Done))

    val factory = new ServiceFactory
    val futTerminate = factory.enableGracefulShutdown(Future.successful(mockBinding),
      Future.successful(Done), mockManagement)(mockSystem)
    futureResult(futTerminate)
    verify(mockBinding).addToCoordinatedShutdown(5.seconds)(mockSystem)
    verify(mockManagement).stopActors()
    verify(mockSystem).terminate()
  }

  it should "not shutdown before the server has been fully started" in {
    val mockBinding = mock[ServerBinding]
    val mockSystem = mock[ActorSystem]
    val mockManagement = mock[ActorManagement]
    when(mockSystem.dispatcher).thenReturn(system.dispatcher)
    val promiseBinding = Promise[ServerBinding]()

    val factory = new ServiceFactory
    factory.enableGracefulShutdown(promiseBinding.future, Future.successful(Done), mockManagement)(mockSystem)

    verify(mockManagement, never()).stopActors()
    verify(mockSystem, never()).terminate()
  }

  it should "not shutdown before the shutdown future has completed" in {
    val mockBinding = mock[ServerBinding]
    val mockSystem = mock[ActorSystem]
    val mockManagement = mock[ActorManagement]
    when(mockSystem.dispatcher).thenReturn(system.dispatcher)
    val promiseShutdown = Promise[Done]()

    val factory = new ServiceFactory
    factory.enableGracefulShutdown(Future.successful(mockBinding), promiseShutdown.future, mockManagement)(mockSystem)

    verify(mockManagement, never()).stopActors()
    verify(mockSystem, never()).terminate()
  }

  it should "shutdown the actor system even if the binding future failed" in {
    val mockSystem = mock[ActorSystem]
    when(mockSystem.terminate()).thenReturn(Future.successful(Terminated))
    when(mockSystem.dispatcher).thenReturn(system.dispatcher)
    val mockManagement = mock[ActorManagement]
    val promiseBinding = Promise[ServerBinding]()

    val factory = new ServiceFactory
    val futureTerminated = factory.enableGracefulShutdown(promiseBinding.future, Future.successful(Done),
      mockManagement)(mockSystem)

    promiseBinding.failure(new IllegalStateException("Test exception"))
    futureResult(futureTerminated)
    verify(mockManagement).stopActors()
    verify(mockSystem).terminate()
  }
