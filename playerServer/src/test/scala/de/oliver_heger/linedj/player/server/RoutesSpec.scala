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
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.testkit.TestKit
import akka.util.ByteString
import de.oliver_heger.linedj.player.engine.radio.RadioSource
import de.oliver_heger.linedj.player.engine.radio.control.RadioControlActor
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.player.server.ServerConfigTestHelper.futureResult
import de.oliver_heger.linedj.player.server.model.RadioModel
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.net.ServerSocket
import java.nio.file.Paths
import scala.concurrent.{Future, Promise}
import scala.util.Using

object RoutesSpec:
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

  /**
    * Returns the URI for a specific path on the test server based on the given
    * configuration.
    *
    * @param config the configuration
    * @param path   the path of the URI
    * @return the URI to invoke this path on the test server
    */
  private def serverUri(config: PlayerServerConfig, path: String): String =
    s"http://localhost:${config.serverPort}$path"
end RoutesSpec

/**
  * Test class for the routes configured by the [[Routes]] module.
  * Unfortunately, due to conflicting Scala 2 and 3 dependencies, it is
  * currently not possible to use the routes testkit of Akka HTTP for this
  * purpose.
  */
class RoutesSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with RadioModel.RadioJsonSupport:
  def this() = this(ActorSystem("RoutesSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  import RoutesSpec.*

  /**
    * Returns a [[PlayerServerConfig]] that can be used to start an HTTP
    * server. It is initialized from a base configuration with an unused server
    * port and the path to the folder containing the test UI.
    *
    * @param baseConfig the base configuration
    * @return the configuration
    */
  private def httpServerConfig(baseConfig: PlayerServerConfig = baseServerConfig): PlayerServerConfig =
    baseConfig.copy(serverPort = freePort(),
      uiContentFolder = Paths.get("playerServer", "src", "test", "resources", "ui"),
      uiPath = "/ui/index.html")

  /**
    * Returns a basic configuration for the player server. The configuration
    * used by tests is typically derived from this base configuration.
    *
    * @return the basic server configuration
    */
  private def baseServerConfig: PlayerServerConfig =
    ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system))

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
  private def runHttpServerTest(config: PlayerServerConfig = baseServerConfig,
                                radioPlayer: RadioPlayer = mock,
                                shutdownPromise: Promise[Done] = Promise())
                               (block: PlayerServerConfig => Unit): Unit =
    val factory = new ServiceFactory
    val serverConfig = httpServerConfig(config)
    val bindings = futureResult(factory.createHttpServer(serverConfig, radioPlayer, shutdownPromise))

    try
      block(serverConfig)
    finally
      futureResult(bindings.unbind())

  /**
    * Helper function for sending a request to the test server and returning
    * the response.
    *
    * @param request the request to be sent
    * @return the response
    */
  private def sendRequest(request: HttpRequest): HttpResponse =
    futureResult(Http().singleRequest(request))

  /**
    * Helper function to unmarshal the given response to a specific target
    * type.
    *
    * @param response the response
    * @param um       the ''Unmarshaller'' instance for this type
    * @tparam B the target type
    * @return the unmarshalled response entity
    */
  private def unmarshal[B](response: HttpResponse)
                          (implicit um: Unmarshaller[HttpResponse, B]): B =
    futureResult(Unmarshal(response).to[B])

  "Routes" should "define a route for accessing the UI" in {
    runHttpServerTest() { config =>
      val uiRequest = HttpRequest(uri = serverUri(config, config.uiPath))
      val response = sendRequest(uiRequest)
      response.status should be(StatusCodes.OK)

      val expectedString = readSource(FileIO.fromPath(config.uiContentFolder.resolve("index.html")))
      val responseString = readSource(response.entity.dataBytes)
      responseString should be(expectedString)
    }
  }

  it should "support the UI route without a prefix" in {
    val orgConfig = httpServerConfig().copy(uiPath = "/index.html")

    runHttpServerTest(orgConfig) { config =>
      val uiRequest = HttpRequest(uri = serverUri(config, config.uiPath))
      val response = sendRequest(uiRequest)
      response.status should be(StatusCodes.OK)

      val expectedString = readSource(FileIO.fromPath(config.uiContentFolder.resolve("index.html")))
      val responseString = readSource(response.entity.dataBytes)
      responseString should be(expectedString)
    }
  }

  it should "define a route to trigger the server shutdown" in {
    val shutdownPromise = Promise[Done]()

    runHttpServerTest(shutdownPromise = shutdownPromise) { config =>
      val shutdownRequest = HttpRequest(uri = serverUri(config, "/api/shutdown"), method = HttpMethods.POST)
      val shutdownResponse = sendRequest(shutdownRequest)
      shutdownResponse.status should be(StatusCodes.Accepted)
      shutdownPromise.isCompleted shouldBe true
      futureResult(shutdownPromise.future) should be(Done)
    }
  }

  it should "define a route to start radio playback" in {
    val radioPlayer = mock[RadioPlayer]

    runHttpServerTest(radioPlayer = radioPlayer) { config =>
      reset(radioPlayer)
      val startPlaybackRequest = HttpRequest(uri = serverUri(config, "/api/radio/playback/start"),
        method = HttpMethods.POST)
      val startResponse = sendRequest(startPlaybackRequest)

      startResponse.status should be(StatusCodes.OK)
      verify(radioPlayer).startPlayback()
    }
  }

  it should "define a route to stop radio playback" in {
    val radioPlayer = mock[RadioPlayer]

    runHttpServerTest(radioPlayer = radioPlayer) { config =>
      reset(radioPlayer)
      val stopPlaybackRequest = HttpRequest(uri = serverUri(config, "/api/radio/playback/stop"),
        method = HttpMethods.POST)
      val stopResponse = sendRequest(stopPlaybackRequest)

      stopResponse.status should be(StatusCodes.OK)
      verify(radioPlayer).stopPlayback()
    }
  }

  it should "define a route to query the current playback status" in {
    val radioPlayer = mock[RadioPlayer]
    val playbackState = RadioControlActor.CurrentPlaybackState(None, playbackActive = true)
    when(radioPlayer.currentPlaybackState).thenReturn(Future.successful(playbackState))

    runHttpServerTest(radioPlayer = radioPlayer) { config =>
      val stateRequest = HttpRequest(uri = serverUri(config, "/api/radio/playback"))
      val stateResponse = sendRequest(stateRequest)

      stateResponse.status should be(StatusCodes.OK)
      val actualState = unmarshal[RadioModel.PlaybackStatus](stateResponse)
      actualState should be(RadioModel.PlaybackStatus(enabled = true))
    }
  }

  it should "handle errors when querying the current playback status" in {
    val radioPlayer = mock[RadioPlayer]
    when(radioPlayer.currentPlaybackState).thenReturn(Future.failed(new IllegalStateException("test exception")))

    runHttpServerTest(radioPlayer = radioPlayer) { config =>
      val stateRequest = HttpRequest(uri = serverUri(config, "/api/radio/playback"))
      val stateResponse = sendRequest(stateRequest)

      stateResponse.status should be(StatusCodes.InternalServerError)
    }
  }

  it should "define a route to query the current radio source if it is defined" in {
    val source = ServerConfigTestHelper.TestRadioSource("current", ranking = 25)
    val radioPlayer = mock[RadioPlayer]
    val playbackState = RadioControlActor.CurrentPlaybackState(Some(source.toRadioSource), playbackActive = false)
    when(radioPlayer.currentPlaybackState).thenReturn(Future.successful(playbackState))
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system),
      List(source))

    runHttpServerTest(config = serverConfig, radioPlayer = radioPlayer) { config =>
      val sourceRequest = HttpRequest(uri = serverUri(config, "/api/radio/sources/current"))
      val sourceResponse = sendRequest(sourceRequest)

      sourceResponse.status should be(StatusCodes.OK)
      val actualSource = unmarshal[RadioModel.RadioSource](sourceResponse)
      actualSource.name should be(source.name)
      actualSource.ranking should be(source.ranking)
      actualSource.id should not be null
    }
  }

  it should "define a route to query the current radio source if it is undefined" in {
    val radioPlayer = mock[RadioPlayer]
    val playbackState = RadioControlActor.CurrentPlaybackState(None, playbackActive = false)
    when(radioPlayer.currentPlaybackState).thenReturn(Future.successful(playbackState))

    runHttpServerTest(radioPlayer = radioPlayer) { config =>
      val sourceRequest = HttpRequest(uri = serverUri(config, "/api/radio/sources/current"))
      val sourceResponse = sendRequest(sourceRequest)

      sourceResponse.status should be(StatusCodes.NoContent)
    }
  }

  it should "handle errors when querying the current source" in {
    val radioPlayer = mock[RadioPlayer]
    when(radioPlayer.currentPlaybackState).thenReturn(Future.failed(new IllegalStateException("test exception")))

    runHttpServerTest(radioPlayer = radioPlayer) { config =>
      val sourceRequest = HttpRequest(uri = serverUri(config, "/api/radio/sources/current"))
      val sourceResponse = sendRequest(sourceRequest)

      sourceResponse.status should be(StatusCodes.InternalServerError)
    }
  }

  it should "define a route to query the existing radio sources" in {
    val sources = (1 to 8).map { idx =>
      ServerConfigTestHelper.TestRadioSource("radioSource" + idx, idx)
    }
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system),
      sources)
    val radioPlayer = mock[RadioPlayer]

    runHttpServerTest(config = serverConfig, radioPlayer = radioPlayer) { config =>
      val sourcesRequest = HttpRequest(uri = serverUri(config, "/api/radio/sources"))
      val sourcesResponse = sendRequest(sourcesRequest)

      sourcesResponse.status should be(StatusCodes.OK)
      val actualSources = unmarshal[RadioModel.RadioSources](sourcesResponse).sources
      val sourceIds = actualSources.map(_.id).toSet
      sourceIds should have size sources.size

      val actualTestSources = actualSources.map { source =>
        ServerConfigTestHelper.TestRadioSource(source.name, source.ranking)
      }
      actualTestSources should contain theSameElementsAs sources
    }
  }

  it should "define a route to set the current radio source" in {
    val source = ServerConfigTestHelper.TestRadioSource("myFavoriteSource", 99)
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system),
      List(source))
    val radioPlayer = mock[RadioPlayer]

    runHttpServerTest(config = serverConfig, radioPlayer = radioPlayer) { config =>
      val sourcesResponse = sendRequest(HttpRequest(uri = serverUri(config, "/api/radio/sources")))
      val allSources = unmarshal[RadioModel.RadioSources](sourcesResponse).sources
      val sourceID = allSources.head.id

      val currentSourceRequest = HttpRequest(method = HttpMethods.POST,
        uri = serverUri(config, "/api/radio/sources/current/" + sourceID))
      val currentSourceResponse = sendRequest(currentSourceRequest)

      currentSourceResponse.status should be(StatusCodes.OK)
      val expectedRadioSource = RadioSource(source.uri)
      verify(radioPlayer).switchToRadioSource(expectedRadioSource)
    }
  }

  it should "handle an unknown source ID" in {
    val radioPlayer = mock[RadioPlayer]

    runHttpServerTest(radioPlayer = radioPlayer) { config =>
      val currentSourceRequest = HttpRequest(method = HttpMethods.POST,
        uri = serverUri(config, "/api/radio/sources/current/nonExistingRadioSourceID"))
      val currentSourceResponse = sendRequest(currentSourceRequest)

      currentSourceResponse.status should be(StatusCodes.NotFound)
      verify(radioPlayer, never()).switchToRadioSource(any())
    }
  }
