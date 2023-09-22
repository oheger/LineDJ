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
import akka.http.scaladsl.model.*
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.testkit.TestKit
import akka.util.ByteString
import de.oliver_heger.linedj.player.engine.radio.RadioSource
import de.oliver_heger.linedj.player.engine.radio.control.RadioControlActor
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.player.server.model.RadioModel
import org.apache.commons.configuration.HierarchicalConfiguration
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.compatible.Assertion
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.net.ServerSocket
import java.nio.file.Paths
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Using}

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
    * @param ec     the execution context
    * @tparam M the materialized type of the source
    * @return a ''Future'' the string content of the source
    */
  private def readSource[M](source: Source[ByteString, M])
                           (implicit mat: Materializer, ec: ExecutionContext): Future[String] =
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    source.runWith(sink).map(_.utf8String)

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
class RoutesSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with RadioModel.RadioJsonSupport:
  def this() = this(ActorSystem("RoutesSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  import RoutesSpec.*

  /** The execution context in implicit scope. */
  private implicit val ec: ExecutionContext = system.dispatcher

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
                               (block: PlayerServerConfig => Future[Assertion]): Future[Assertion] =
    val factory = new ServiceFactory
    val serverConfig = httpServerConfig(config)

    val promiseAssertion = Promise[Assertion]()
    factory.createHttpServer(serverConfig, radioPlayer, shutdownPromise) onComplete {
      case Success(startUpData) =>
        block(serverConfig) onComplete { triedResult =>
          startUpData.binding.unbind() onComplete { _ =>
            promiseAssertion.complete(triedResult)
          }
        }

      case Failure(exception) =>
        promiseAssertion.failure(exception)
    }

    promiseAssertion.future

  /**
    * Helper function for sending a request to the test server and returning
    * the response.
    *
    * @param request the request to be sent
    * @return a ''Future'' with the response
    */
  private def sendRequest(request: HttpRequest): Future[HttpResponse] = Http().singleRequest(request)

  /**
    * Helper function for sending a request to the test server and checking the
    * response status. If successful, a ''Future'' with the response is
    * returned.
    *
    * @param request        the request to be sent
    * @param expectedStatus the expected status code of the response
    * @return a ''Future'' with the response
    */
  private def sendAndCheckRequest(request: HttpRequest,
                                  expectedStatus: StatusCode = StatusCodes.OK): Future[HttpResponse] =
    sendRequest(request) filter (_.status == expectedStatus)

  /**
    * Helper function to unmarshal the given response to a specific target
    * type.
    *
    * @param response the response
    * @param um       the ''Unmarshaller'' instance for this type
    * @tparam B the target type
    * @return a ''Future'' with the unmarshalled response entity
    */
  private def unmarshal[B](response: HttpResponse)
                          (implicit um: Unmarshaller[HttpResponse, B]): Future[B] =
    Unmarshal(response).to[B]

  "Routes" should "define a route for accessing the UI" in {
    runHttpServerTest() { config =>
      val uiRequest = HttpRequest(uri = serverUri(config, config.uiPath))
      sendRequest(uiRequest) flatMap { response =>
        response.status should be(StatusCodes.OK)

        for
          expectedString <- readSource(FileIO.fromPath(config.uiContentFolder.resolve("index.html")))
          responseString <- readSource(response.entity.dataBytes)
        yield responseString should be(expectedString)
      }
    }
  }

  it should "support the UI route without a prefix" in {
    val orgConfig = httpServerConfig().copy(uiPath = "/index.html")

    runHttpServerTest(orgConfig) { config =>
      val uiRequest = HttpRequest(uri = serverUri(config, config.uiPath))
      sendRequest(uiRequest) flatMap { response =>
        response.status should be(StatusCodes.OK)

        for
          expectedString <- readSource(FileIO.fromPath(config.uiContentFolder.resolve("index.html")))
          responseString <- readSource(response.entity.dataBytes)
        yield responseString should be(expectedString)
      }
    }
  }

  it should "define a route to trigger the server shutdown" in {
    val shutdownPromise = Promise[Done]()

    runHttpServerTest(shutdownPromise = shutdownPromise) { config =>
      val shutdownRequest = HttpRequest(uri = serverUri(config, "/api/shutdown"), method = HttpMethods.POST)
      sendRequest(shutdownRequest) map { shutdownResponse =>
        shutdownResponse.status should be(StatusCodes.Accepted)
        shutdownPromise.isCompleted shouldBe true
        shutdownPromise.future.value should be(Some(Success(Done)))
      }
    }
  }

  it should "define a route to start radio playback" in {
    val radioPlayer = mock[RadioPlayer]

    runHttpServerTest(radioPlayer = radioPlayer) { config =>
      reset(radioPlayer)
      val startPlaybackRequest = HttpRequest(uri = serverUri(config, "/api/radio/playback/start"),
        method = HttpMethods.POST)

      sendRequest(startPlaybackRequest) map { startResponse =>
        verify(radioPlayer).startPlayback()
        startResponse.status should be(StatusCodes.OK)
      }
    }
  }

  it should "define a route to stop radio playback" in {
    val radioPlayer = mock[RadioPlayer]

    runHttpServerTest(radioPlayer = radioPlayer) { config =>
      reset(radioPlayer)
      val stopPlaybackRequest = HttpRequest(uri = serverUri(config, "/api/radio/playback/stop"),
        method = HttpMethods.POST)

      sendRequest(stopPlaybackRequest) map { stopResponse =>
        verify(radioPlayer).stopPlayback()
        stopResponse.status should be(StatusCodes.OK)
      }
    }
  }

  it should "define a route to query the current playback status" in {
    val radioPlayer = mock[RadioPlayer]
    val playbackState = RadioControlActor.CurrentPlaybackState(None, None, playbackActive = true)
    when(radioPlayer.currentPlaybackState).thenReturn(Future.successful(playbackState))

    runHttpServerTest(radioPlayer = radioPlayer) { config =>
      val stateRequest = HttpRequest(uri = serverUri(config, "/api/radio/playback"))

      for
        stateResponse <- sendAndCheckRequest(stateRequest)
        actualState <- unmarshal[RadioModel.PlaybackStatus](stateResponse)
      yield actualState should be(RadioModel.PlaybackStatus(enabled = true))
    }
  }

  it should "handle errors when querying the current playback status" in {
    val radioPlayer = mock[RadioPlayer]
    when(radioPlayer.currentPlaybackState).thenReturn(Future.failed(new IllegalStateException("test exception")))

    runHttpServerTest(radioPlayer = radioPlayer) { config =>
      val stateRequest = HttpRequest(uri = serverUri(config, "/api/radio/playback"))

      sendRequest(stateRequest) map { stateResponse =>
        stateResponse.status should be(StatusCodes.InternalServerError)
      }
    }
  }

  it should "define a route to query the current radio source if it is defined" in {
    val sourceCurrent = ServerConfigTestHelper.TestRadioSource("current", ranking = 25)
    val sourceSelected = ServerConfigTestHelper.TestRadioSource("selected", ranking = 24)
    val radioPlayer = mock[RadioPlayer]
    val playbackState = RadioControlActor.CurrentPlaybackState(Some(sourceCurrent.toRadioSource),
      Some(sourceSelected.toRadioSource), playbackActive = false)
    when(radioPlayer.currentPlaybackState).thenReturn(Future.successful(playbackState))
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system),
      List(sourceCurrent, sourceSelected))

    runHttpServerTest(config = serverConfig, radioPlayer = radioPlayer) { config =>
      val sourceRequest = HttpRequest(uri = serverUri(config, "/api/radio/sources/current"))

      for
        sourceResponse <- sendAndCheckRequest(sourceRequest)
        actualSource <- unmarshal[RadioModel.RadioSource](sourceResponse)
      yield
        actualSource.name should be(sourceSelected.name)
        actualSource.ranking should be(sourceSelected.ranking)
        actualSource.id should not be null
    }
  }

  it should "define a route to query the current radio source if it is undefined" in {
    val radioPlayer = mock[RadioPlayer]
    val playbackState = RadioControlActor.CurrentPlaybackState(None, None, playbackActive = false)
    when(radioPlayer.currentPlaybackState).thenReturn(Future.successful(playbackState))

    runHttpServerTest(radioPlayer = radioPlayer) { config =>
      val sourceRequest = HttpRequest(uri = serverUri(config, "/api/radio/sources/current"))

      sendRequest(sourceRequest) map { sourceResponse =>
        sourceResponse.status should be(StatusCodes.NoContent)
      }
    }
  }

  it should "handle errors when querying the current source" in {
    val radioPlayer = mock[RadioPlayer]
    when(radioPlayer.currentPlaybackState).thenReturn(Future.failed(new IllegalStateException("test exception")))

    runHttpServerTest(radioPlayer = radioPlayer) { config =>
      val sourceRequest = HttpRequest(uri = serverUri(config, "/api/radio/sources/current"))

      sendRequest(sourceRequest) map { sourceResponse =>
        sourceResponse.status should be(StatusCodes.InternalServerError)
      }
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

      for
        sourcesResponse <- sendAndCheckRequest(sourcesRequest)
        actualSources <- unmarshal[RadioModel.RadioSources](sourcesResponse)
      yield
        val sourceIds = actualSources.sources.map(_.id).toSet
        sourceIds should have size sources.size

        val actualTestSources = actualSources.sources.map { source =>
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
      for
        sourcesResponse <- sendRequest(HttpRequest(uri = serverUri(config, "/api/radio/sources")))
        allSourcesObj <- unmarshal[RadioModel.RadioSources](sourcesResponse)
        allSources = allSourcesObj.sources
        sourceID = allSources.head.id

        currentSourceRequest = HttpRequest(method = HttpMethods.POST,
          uri = serverUri(config, "/api/radio/sources/current/" + sourceID))
        currentSourceResponse <- sendRequest(currentSourceRequest)
      yield
        val expectedRadioSource = RadioSource(source.uri)
        verify(radioPlayer).switchToRadioSource(expectedRadioSource)
        currentSourceResponse.status should be(StatusCodes.OK)
    }
  }

  it should "update the current radio source in the current config" in {
    val source = ServerConfigTestHelper.TestRadioSource("myNewCurrentFavoriteSource", 88)
    val currentConfig = new HierarchicalConfiguration
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system),
      List(source)).copy(optCurrentConfig = Some(currentConfig))
    val radioPlayer = mock[RadioPlayer]

    runHttpServerTest(config = serverConfig, radioPlayer = radioPlayer) { config =>
      for
        sourcesResponse <- sendRequest(HttpRequest(uri = serverUri(config, "/api/radio/sources")))
        allSourcesObj <- unmarshal[RadioModel.RadioSources](sourcesResponse)
        allSources = allSourcesObj.sources
        sourceID = allSources.head.id

        currentSourceRequest = HttpRequest(method = HttpMethods.POST,
          uri = serverUri(config, "/api/radio/sources/current/" + sourceID))
        _ <- sendRequest(currentSourceRequest)
      yield
        currentConfig.getString(PlayerServerConfig.PropCurrentSource) should be(source.name)
    }
  }

  it should "handle an unknown source ID" in {
    val radioPlayer = mock[RadioPlayer]

    runHttpServerTest(radioPlayer = radioPlayer) { config =>
      val currentSourceRequest = HttpRequest(method = HttpMethods.POST,
        uri = serverUri(config, "/api/radio/sources/current/nonExistingRadioSourceID"))

      sendRequest(currentSourceRequest) map { currentSourceResponse =>
        verify(radioPlayer, never()).switchToRadioSource(any())
        currentSourceResponse.status should be(StatusCodes.NotFound)
      }
    }
  }
