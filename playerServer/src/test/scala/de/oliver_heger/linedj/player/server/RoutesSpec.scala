/*
 * Copyright 2015-2025 The Developers Team.
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
import de.oliver_heger.linedj.player.engine.radio.*
import de.oliver_heger.linedj.player.server.model.RadioModel
import org.apache.commons.configuration.HierarchicalConfiguration
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.ws.TextMessage
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import spray.json.*

import java.nio.file.{Files, Paths}
import scala.concurrent.{Future, Promise}
import scala.util.Success

/**
  * Test class for the routes configured by the [[Routes]] module.
  */
class RoutesSpec extends AnyFlatSpec with BeforeAndAfterAll with Matchers with ScalatestRouteTest
  with MockitoSugar with RadioModel.RadioJsonSupport:

  /** The test kit for testing typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()
    super.afterAll()

  /**
    * Returns a [[PlayerServerConfig]] that can be used to start an HTTP
    * server. It is initialized from a base configuration with an unused server
    * port and the path to the folder containing the test UI. Unfortunately,
    * the latter is different when the test is launched from the IDE or
    * directly via SBT.
    *
    * @param baseConfig the base configuration
    * @return the configuration
    */
  private def httpServerConfig(baseConfig: PlayerServerConfig = baseServerConfig): PlayerServerConfig =
    val ProjectName = "playerServer"
    val currentDir = Paths.get("").toAbsolutePath
    val projectDir = if currentDir.getFileName.toString == ProjectName then currentDir
    else currentDir.resolve(ProjectName)
    val uiFolderPathRelative = Paths.get("src", "test", "resources", "ui")
    val uiFolderPath = projectDir.resolve(uiFolderPathRelative).normalize()

    baseConfig.copy(uiContentFolder = uiFolderPath, uiPath = "/ui/index.html")

  /**
    * Returns a basic configuration for the player server. The configuration
    * used by tests is typically derived from this base configuration.
    *
    * @return the basic server configuration
    */
  private def baseServerConfig: PlayerServerConfig =
    ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system))

  /**
    * Returns the route to be used for testing.
    *
    * @param config          the server configuration
    * @param radioPlayer     the radio player
    * @param shutdownPromise the promise to trigger shutdown
    * @return the route to test against
    */
  private def testRoute(config: PlayerServerConfig = baseServerConfig,
                        radioPlayer: RadioPlayer = mock,
                        shutdownPromise: Promise[Done] = Promise()): Route =
    Routes.route(config, radioPlayer, shutdownPromise)

  "Routes" should "define a route for accessing the UI" in :
    val config = httpServerConfig()
    val indexPage = Files.readString(config.uiContentFolder.resolve("index.html"))

    Get(config.uiPath) ~> testRoute(config) ~> check:
      responseAs[String] should be(indexPage)

  it should "support the UI route without a prefix" in :
    val config = httpServerConfig().copy(uiPath = "/index.html")
    val indexPage = Files.readString(config.uiContentFolder.resolve("index.html"))

    Get(config.uiPath) ~> testRoute(config) ~> check:
      responseAs[String] should be(indexPage)

  it should "serve the UI from the classpath if configured" in :
    val config = httpServerConfig().copy(optUiContentResource = Some("ui-resource"))
    // The path is different when executed from the IDE and from SBT.
    val modulePath = Paths.get("playerServer/src/test/resources/ui-resource")
    val localPath = if Files.exists(modulePath) then modulePath
    else Paths.get("src/test/resources/ui-resource")
    val indexPage = Files.readString(localPath.toAbsolutePath.resolve("index.html"))

    Get(config.uiPath) ~> testRoute(config) ~> check:
      responseAs[String] should be(indexPage)

  it should "define a route to trigger the server shutdown" in :
    val shutdownPromise = Promise[Done]()

    Post("/api/shutdown") ~> testRoute(shutdownPromise = shutdownPromise) ~> check:
      status should be(StatusCodes.Accepted)
      shutdownPromise.isCompleted shouldBe true
      shutdownPromise.future.value should be(Some(Success(Done)))

  it should "define a route to start radio playback" in :
    val radioPlayer = mock[RadioPlayer]

    Post("/api/radio/playback/start") ~> testRoute(radioPlayer = radioPlayer) ~> check:
      status should be(StatusCodes.OK)
      verify(radioPlayer).startPlayback()

  it should "define a route to stop radio playback" in :
    val radioPlayer = mock[RadioPlayer]

    Post("/api/radio/playback/stop") ~> testRoute(radioPlayer = radioPlayer) ~> check:
      status should be(StatusCodes.OK)
      verify(radioPlayer).stopPlayback()

  it should "define a route to query the current playback status" in :
    val radioPlayer = mock[RadioPlayer]
    val playbackState = RadioControlActor.CurrentPlaybackState(None, None, playbackActive = true, None)
    when(radioPlayer.currentPlaybackState).thenReturn(Future.successful(playbackState))

    Get("/api/radio/playback") ~> testRoute(radioPlayer = radioPlayer) ~> check:
      status should be(StatusCodes.OK)
      val playbackStatus = responseAs[RadioModel.PlaybackStatus]
      playbackStatus should be(RadioModel.PlaybackStatus(enabled = true))

  it should "handle errors when querying the current playback status" in :
    val radioPlayer = mock[RadioPlayer]
    when(radioPlayer.currentPlaybackState).thenReturn(Future.failed(new IllegalStateException("test exception")))

    Get("/api/radio/playback") ~> testRoute(radioPlayer = radioPlayer) ~> check:
      status should be(StatusCodes.InternalServerError)

  it should "define a route to query the current radio source if it is defined" in :
    val sourceCurrent = ServerConfigTestHelper.TestRadioSource("current", ranking = 25)
    val sourceSelected = ServerConfigTestHelper.TestRadioSource("selected", ranking = 24)
    val radioPlayer = mock[RadioPlayer]
    val playbackState = RadioControlActor.CurrentPlaybackState(
      Some(sourceCurrent.toRadioSource),
      Some(sourceSelected.toRadioSource),
      playbackActive = false,
      None
    )
    when(radioPlayer.currentPlaybackState).thenReturn(Future.successful(playbackState))
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system),
      List(sourceCurrent, sourceSelected))

    Get("/api/radio/sources/current") ~> testRoute(config = serverConfig, radioPlayer = radioPlayer) ~> check:
      status should be(StatusCodes.OK)
      val currentSource = responseAs[RadioModel.RadioSource]
      currentSource.name should be(sourceSelected.name)
      currentSource.ranking should be(sourceSelected.ranking)
      currentSource.id should be(sourceSelected.id)

  it should "define a route to query the current radio source if it is undefined" in :
    val radioPlayer = mock[RadioPlayer]
    val playbackState = RadioControlActor.CurrentPlaybackState(None, None, playbackActive = false, None)
    when(radioPlayer.currentPlaybackState).thenReturn(Future.successful(playbackState))

    Get("/api/radio/sources/current?full=false") ~> testRoute(radioPlayer = radioPlayer) ~> check:
      status should be(StatusCodes.NoContent)

  it should "handle errors when querying the current source" in :
    val radioPlayer = mock[RadioPlayer]
    when(radioPlayer.currentPlaybackState).thenReturn(Future.failed(new IllegalStateException("test exception")))

    Get("/api/radio/sources/current") ~> testRoute(radioPlayer = radioPlayer) ~> check:
      status should be(StatusCodes.InternalServerError)

  it should "define a route to query the current source status if all sources are defined" in :
    val sourceCurrent = ServerConfigTestHelper.TestRadioSource("current", ranking = 25)
    val sourceSelected = ServerConfigTestHelper.TestRadioSource("selected", ranking = 24)
    val radioPlayer = mock[RadioPlayer]
    val playbackState = RadioControlActor.CurrentPlaybackState(
      Some(sourceCurrent.toRadioSource),
      Some(sourceSelected.toRadioSource),
      playbackActive = false,
      None
    )
    when(radioPlayer.currentPlaybackState).thenReturn(Future.successful(playbackState))
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system),
      List(sourceCurrent, sourceSelected))

    Get("/api/radio/sources/current?full=true") ~> testRoute(serverConfig, radioPlayer) ~> check:
      status should be(StatusCodes.OK)
      val sourceStatus = responseAs[RadioModel.RadioSourceStatus]
      sourceStatus.currentSourceId should be(Some(sourceSelected.id))
      sourceStatus.replacementSourceId should be(Some(sourceCurrent.id))
      sourceStatus.titleInfo shouldBe empty

  it should "define a route to query the current source status including title information" in :
    val sourceCurrent = ServerConfigTestHelper.TestRadioSource("current", ranking = 25)
    val sourceSelected = ServerConfigTestHelper.TestRadioSource("selected", ranking = 24)
    val Title = "ACDC / Highway to Hell"
    val radioPlayer = mock[RadioPlayer]
    val playbackState = RadioControlActor.CurrentPlaybackState(
      Some(sourceCurrent.toRadioSource),
      Some(sourceSelected.toRadioSource),
      playbackActive = true,
      Some(CurrentMetadata(s"StreamTitle='$Title';"))
    )
    when(radioPlayer.currentPlaybackState).thenReturn(Future.successful(playbackState))
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system),
      List(sourceCurrent, sourceSelected))

    Get("/api/radio/sources/current?full=true") ~> testRoute(serverConfig, radioPlayer) ~> check:
      status should be(StatusCodes.OK)
      val sourceStatus = responseAs[RadioModel.RadioSourceStatus]
      sourceStatus.currentSourceId should be(Some(sourceSelected.id))
      sourceStatus.replacementSourceId should be(Some(sourceCurrent.id))
      sourceStatus.titleInfo should be(Some(Title))

  it should "define a route to query the current source status if no sources are defined" in :
    val radioPlayer = mock[RadioPlayer]
    val playbackState = RadioControlActor.CurrentPlaybackState(None, None, playbackActive = false, None)
    when(radioPlayer.currentPlaybackState).thenReturn(Future.successful(playbackState))
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system))

    Get("/api/radio/sources/current?full=true") ~> testRoute(serverConfig, radioPlayer) ~> check:
      status should be(StatusCodes.OK)
      val actualSource = responseAs[RadioModel.RadioSourceStatus]
      actualSource.currentSourceId shouldBe empty
      actualSource.replacementSourceId shouldBe empty

  it should "return an empty replacement source ID if the current source equals the selected source" in :
    val sourceCurrent = ServerConfigTestHelper.TestRadioSource("current", ranking = 25)
    val radioPlayer = mock[RadioPlayer]
    val playbackState = RadioControlActor.CurrentPlaybackState(
      Some(sourceCurrent.toRadioSource),
      Some(sourceCurrent.toRadioSource),
      playbackActive = false,
      None
    )
    when(radioPlayer.currentPlaybackState).thenReturn(Future.successful(playbackState))
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system),
      List(sourceCurrent))

    Get("/api/radio/sources/current?full=true") ~> testRoute(serverConfig, radioPlayer) ~> check:
      status should be(StatusCodes.OK)
      val sourceStatus = responseAs[RadioModel.RadioSourceStatus]
      sourceStatus.currentSourceId should be(Some(sourceCurrent.id))
      sourceStatus.replacementSourceId shouldBe empty

  it should "handle errors when querying the full source status" in :
    val radioPlayer = mock[RadioPlayer]
    when(radioPlayer.currentPlaybackState).thenReturn(Future.failed(new IllegalStateException("test exception")))

    Get("/api/radio/sources/current?full=true") ~> testRoute(radioPlayer = radioPlayer) ~> check:
      status should be(StatusCodes.InternalServerError)

  it should "define a route to query the existing radio sources" in :
    val sources = (1 to 8).map { idx =>
      ServerConfigTestHelper.TestRadioSource("radioSource" + idx, idx)
    }
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(
      ServerConfigTestHelper.actorCreator(system),
      sources
    )
    val radioPlayer = mock[RadioPlayer]

    Get("/api/radio/sources") ~> testRoute(serverConfig, radioPlayer) ~> check:
      status should be(StatusCodes.OK)
      val actualSources = responseAs[RadioModel.RadioSources]
      val sourceIds = actualSources.sources.map(_.id).toSet
      sourceIds should have size sources.size

      val actualTestSources = actualSources.sources.map { source =>
        ServerConfigTestHelper.TestRadioSource(source.name, source.ranking)
      }
      actualTestSources should contain theSameElementsAs sources

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

    Get("/api/radio/sources") ~> testRoute(serverConfig, radioPlayer) ~> check:
      status should be(StatusCodes.OK)
      val actualSources = responseAs[RadioModel.RadioSources]
      actualSources.sources.map(src => src.copy(id = "")) should contain allOf(favoriteModel1, favoriteModel2)

  it should "define a route to set the current radio source" in :
    val source = ServerConfigTestHelper.TestRadioSource("myFavoriteSource", 99)
    val expectedRadioSource = RadioSource(source.uri)
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system),
      List(source))
    val radioPlayer = mock[RadioPlayer]
    val route = testRoute(serverConfig, radioPlayer)

    Get("/api/radio/sources") ~> route ~> check:
      val allSources = responseAs[RadioModel.RadioSources].sources
      val sourceID = allSources.head.id

      Post("/api/radio/sources/current/" + sourceID) ~> route ~> check:
        verify(radioPlayer).switchToRadioSource(expectedRadioSource)
        status should be(StatusCodes.OK)

  it should "update the current radio source in the current config" in :
    val source = ServerConfigTestHelper.TestRadioSource("myNewCurrentFavoriteSource", 88)
    val currentConfig = new HierarchicalConfiguration
    val serverConfig = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system),
      List(source)).copy(optCurrentConfig = Some(currentConfig))
    val radioPlayer = mock[RadioPlayer]
    val route = testRoute(serverConfig, radioPlayer)

    Get("/api/radio/sources") ~> route ~> check:
      val allSources = responseAs[RadioModel.RadioSources].sources
      val sourceID = allSources.head.id

      Post("/api/radio/sources/current/" + sourceID) ~> route ~> check:
        currentConfig.getString(PlayerServerConfig.PropCurrentSource) should be(source.name)

  it should "handle an unknown source ID" in :
    val radioPlayer = mock[RadioPlayer]

    Post("/api/radio/sources/current/nonExistingRadioSourceID") ~> testRoute(radioPlayer = radioPlayer) ~> check:
      verify(radioPlayer, never()).switchToRadioSource(any())
      status should be(StatusCodes.NotFound)

  it should "define a route to register for radio messages" in :
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
    val wsClient = WSProbe()

    def nextMessage: RadioModel.RadioMessage =
      wsClient.expectMessage() match
        case tm: TextMessage.Strict =>
          val jsonAst = tm.text.parseJson
          jsonAst.convertTo[RadioModel.RadioMessage]
        case m =>
          fail("Unexpected message: " + m)

    WS("/api/radio/events", wsClient.flow) ~> testRoute(serverConfig, radioPlayer) ~> check:
      isWebSocketUpgrade shouldBe true

      sendEvent(RadioSourceChangedEvent(radioSource1.toRadioSource))
      nextMessage should be(RadioModel.RadioMessage(RadioModel.MessageTypeSourceChanged, radioSource1ID))

      sendEvent(RadioSourceReplacementStartEvent(radioSource1.toRadioSource, radioSource2.toRadioSource))
      nextMessage should be(RadioModel.RadioMessage(RadioModel.MessageTypeReplacementStart, radioSource2ID))
