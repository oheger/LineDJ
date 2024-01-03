/*
 * Copyright 2015-2024 The Developers Team.
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

import de.oliver_heger.linedj.player.engine.actors.EventManagerActor
import de.oliver_heger.linedj.player.engine.radio.control.RadioControlActor
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.player.engine.radio.{RadioEvent, RadioSource, RadioSourceChangedEvent, RadioSourceReplacementStartEvent}
import de.oliver_heger.linedj.player.server.model.RadioModel
import org.apache.commons.configuration.HierarchicalConfiguration
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import org.apache.pekko.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{FileIO, Flow, Keep, Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.compatible.Assertion
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import spray.json.*

import java.net.ServerSocket
import java.nio.file.Paths
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
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
  * currently not possible to use the routes testkit of Pekko HTTP for this
  * purpose.
  */
class RoutesSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with RadioModel.RadioJsonSupport:
  def this() = this(ActorSystem("RoutesSpec"))

  /** The test kit for testing typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    testKit.shutdownTestKit()
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
        actualSource.id should be(sourceSelected.id)
    }
  }

  it should "define a route to query the current radio source if it is undefined" in {
    val radioPlayer = mock[RadioPlayer]
    val playbackState = RadioControlActor.CurrentPlaybackState(None, None, playbackActive = false)
    when(radioPlayer.currentPlaybackState).thenReturn(Future.successful(playbackState))

    runHttpServerTest(radioPlayer = radioPlayer) { config =>
      val sourceRequest = HttpRequest(uri = serverUri(config, "/api/radio/sources/current?full=false"))

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

  it should "define a route to query the current source status if all sources are defined" in {
    val sourceCurrent = ServerConfigTestHelper.TestRadioSource("current", ranking = 25)
    val sourceSelected = ServerConfigTestHelper.TestRadioSource("selected", ranking = 24)
    val radioPlayer = mock[RadioPlayer]
    val playbackState = RadioControlActor.CurrentPlaybackState(Some(sourceCurrent.toRadioSource),
      Some(sourceSelected.toRadioSource), playbackActive = false)
    when(radioPlayer.currentPlaybackState).thenReturn(Future.successful(playbackState))
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system),
      List(sourceCurrent, sourceSelected))

    runHttpServerTest(config = serverConfig, radioPlayer = radioPlayer) { config =>
      val sourceRequest = HttpRequest(uri = serverUri(config, "/api/radio/sources/current?full=true"))

      for
        sourceResponse <- sendAndCheckRequest(sourceRequest)
        actualSource <- unmarshal[RadioModel.RadioSourceStatus](sourceResponse)
      yield
        actualSource.currentSourceId should be(Some(sourceSelected.id))
        actualSource.replacementSourceId should be(Some(sourceCurrent.id))
    }
  }

  it should "define a route to query the current source status if no sources are defined" in {
    val radioPlayer = mock[RadioPlayer]
    val playbackState = RadioControlActor.CurrentPlaybackState(None, None, playbackActive = false)
    when(radioPlayer.currentPlaybackState).thenReturn(Future.successful(playbackState))
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system))

    runHttpServerTest(config = serverConfig, radioPlayer = radioPlayer) { config =>
      val sourceRequest = HttpRequest(uri = serverUri(config, "/api/radio/sources/current?full=true"))

      for
        sourceResponse <- sendAndCheckRequest(sourceRequest)
        actualSource <- unmarshal[RadioModel.RadioSourceStatus](sourceResponse)
      yield
        actualSource.currentSourceId shouldBe empty
        actualSource.replacementSourceId shouldBe empty
    }
  }

  it should "return an empty replacement source ID if the current source equals the selected source" in {
    val sourceCurrent = ServerConfigTestHelper.TestRadioSource("current", ranking = 25)
    val radioPlayer = mock[RadioPlayer]
    val playbackState = RadioControlActor.CurrentPlaybackState(Some(sourceCurrent.toRadioSource),
      Some(sourceCurrent.toRadioSource), playbackActive = false)
    when(radioPlayer.currentPlaybackState).thenReturn(Future.successful(playbackState))
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system),
      List(sourceCurrent))

    runHttpServerTest(config = serverConfig, radioPlayer = radioPlayer) { config =>
      val sourceRequest = HttpRequest(uri = serverUri(config, "/api/radio/sources/current?full=true"))

      for
        sourceResponse <- sendAndCheckRequest(sourceRequest)
        actualSource <- unmarshal[RadioModel.RadioSourceStatus](sourceResponse)
      yield
        actualSource.currentSourceId should be(Some(sourceCurrent.id))
        actualSource.replacementSourceId shouldBe empty
    }
  }

  it should "handle errors when querying the full source status" in {
    val radioPlayer = mock[RadioPlayer]
    when(radioPlayer.currentPlaybackState).thenReturn(Future.failed(new IllegalStateException("test exception")))

    runHttpServerTest(radioPlayer = radioPlayer) { config =>
      val sourceRequest = HttpRequest(uri = serverUri(config, "/api/radio/sources/current?full=true"))

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
  
  it should "define a route to query existing radio sources that handles favorite sources" in :
    val favorite1 = ServerConfigTestHelper.TestRadioSource("f1", favoriteIndex = 0, optFavoriteName = Some("Favorite"))
    val favorite2 = ServerConfigTestHelper.TestRadioSource("f2", favoriteIndex = 1)
    val otherSources = (1 to 4).map { idx =>
      ServerConfigTestHelper.TestRadioSource("radioSource" + idx, idx)
    }.toList
    val sources = favorite2 :: otherSources.appended(favorite1)
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system),
      sources)
    val radioPlayer = mock[RadioPlayer]
    val favoriteModel1 = RadioModel.RadioSource("", "f1", 0, 0, "Favorite")
    val favoriteModel2 = RadioModel.RadioSource("", "f2", 0, 1, "f2")

    runHttpServerTest(config = serverConfig, radioPlayer = radioPlayer) { config =>
      val sourcesRequest = HttpRequest(uri = serverUri(config, "/api/radio/sources"))

      for
        sourcesResponse <- sendAndCheckRequest(sourcesRequest)
        actualSources <- unmarshal[RadioModel.RadioSources](sourcesResponse)
      yield
        actualSources.sources.map(src => src.copy(id = "")) should contain allOf(favoriteModel1, favoriteModel2)
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

  it should "define a route to register for radio messages" in {
    val eventActor = testKit.spawn(EventManagerActor[RadioEvent]())

    def sendEvent(event: RadioEvent): Unit =
      eventActor ! EventManagerActor.Publish(event)

    val radioSource1 = ServerConfigTestHelper.TestRadioSource("someTestSource", 11)
    val radioSource1ID = "WtifS/vkv2YN5l9LVjuuDltlmqo=" // calculated hash
    val radioSource2 = ServerConfigTestHelper.TestRadioSource("anotherTestSource", 7)
    val radioSource2ID = "dcUrULxnshBcQ2uSguf6WMQqvSw="
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(sources = List(radioSource1, radioSource2),
      creator = ServerConfigTestHelper.actorCreator(system, Some(testKit)))
    val radioPlayer = mock[RadioPlayer]
    when(radioPlayer.config).thenReturn(serverConfig.radioPlayerConfig)
    when(radioPlayer.addEventListener(any())).thenAnswer((invocation: InvocationOnMock) =>
      val listener = invocation.getArgument(0, classOf[ActorRef[RadioEvent]])
      eventActor ! EventManagerActor.RegisterListener(listener))

    val messageQueue = new LinkedBlockingQueue[TextMessage.Strict]
    val queueSink: Sink[Message, Future[Done]] = Sink.foreach {
      case message: TextMessage.Strict => messageQueue offer message
      case m => fail("Unexpected message: " + m)
    }
    val outSource = Source.maybe[Message]
    val flow = Flow.fromSinkAndSourceMat(queueSink, outSource)(Keep.right)

    def nextMessage: RadioModel.RadioMessage =
      val textMessage = messageQueue.poll(3, TimeUnit.SECONDS)
      textMessage should not be null
      val jsonAst = textMessage.text.parseJson
      jsonAst.convertTo[RadioModel.RadioMessage]

    runHttpServerTest(serverConfig, radioPlayer) { config =>
      val request = WebSocketRequest(s"ws://localhost:${config.serverPort}/api/radio/events")
      val (futResponse, promise) = Http().singleWebSocketRequest(request, flow)

      futResponse map { upgrade =>
        upgrade.response.status should be(StatusCodes.SwitchingProtocols)

        sendEvent(RadioSourceChangedEvent(radioSource1.toRadioSource))
        nextMessage should be(RadioModel.RadioMessage(RadioModel.MessageTypeSourceChanged, radioSource1ID))

        sendEvent(RadioSourceReplacementStartEvent(radioSource1.toRadioSource, radioSource2.toRadioSource))
        val message2 = nextMessage
        promise.success(None) // Close the web socket connection.
        message2 should be(RadioModel.RadioMessage(RadioModel.MessageTypeReplacementStart, radioSource2ID))
      }
    }
  }
