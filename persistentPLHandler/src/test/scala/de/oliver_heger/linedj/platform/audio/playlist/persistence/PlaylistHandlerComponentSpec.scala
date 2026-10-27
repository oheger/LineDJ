/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio.playlist.persistence

import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.app.ShutdownHandler.ShutdownObserver
import de.oliver_heger.linedj.platform.app.{ClientApplicationContext, ShutdownHandler}
import de.oliver_heger.linedj.platform.audio.playlist.persistence.PlaylistLoader.LoaderFunc
import de.oliver_heger.linedj.platform.audio2.playlist.{MediaFileInfoResolverService, Playlist, PlaylistService}
import de.oliver_heger.linedj.shared.actors.{ActorFactory, BackoffActor}
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.mockito.Mockito.*
import org.osgi.service.component.ComponentContext
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.*
import scala.concurrent.{Future, Promise}

object PlaylistHandlerComponentSpec:
  /** Path to the playlist file. */
  private val PathPlaylist = Paths get "persistedPlaylist.json"

  /** Path to the position file. */
  private val PathPosition = Paths get "position.json"

  /** The auto save interval from the configuration. */
  private val AutoSaveInterval = 90.seconds

  /** The maximum size of a playlist file. */
  private val MaxFileSize = 8 * 1024
end PlaylistHandlerComponentSpec

/**
  * Test class for [[PlaylistHandlerComponent]].
  */
class PlaylistHandlerComponentSpec(testSystem: ActorSystem) extends TestKit(testSystem), AnyFlatSpecLike,
  BeforeAndAfterAll, Matchers, MockitoSugar, OptionValues:
  def this() = this(ActorSystem("PlaylistHandlerComponentSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import ActorFactory.given
  import PlaylistHandlerComponentSpec.*

  "A PlaylistHandlerComponent" should "create correct default factories" in :
    val component = new PlaylistHandlerComponent

    component.saveServiceFactory should be(PlaylistSaveService.create)
    component.saveActorFactory should be(PlaylistSaveActor.newInstance)
    component.loaderFuncFactory should be(PlaylistLoader.default)
    component.loaderActorFactory should be(PlaylistLoaderActor.newInstance)

  it should "create correct actors on activation" in :
    val helper = new ComponentTestHelper

    helper.activate()
      .expectActorCreationCount(1)

  it should "not create actors if the configuration is invalid" in :
    val helper = new ComponentTestHelper
    helper.config.clearProperty(PlaylistHandlerConfig.PropPositionPath)

    helper.activate()
      .expectActorCreationCount(0)
      .deactivate()

    helper.messageBus.expectNoMessage(100.millis)

  it should "release resources on deactivation" in :
    val helper = new ComponentTestHelper

    helper.activate()
      .expectShutdownObserverRegistration()
      .completeShutdownFuture()
      .deactivate()
      .verifyActorsStopped()
      .expectShutdownObserverUnregistration()

  it should "register a correct shutdown observer" in :
    val helper = new ComponentTestHelper

    helper.activate()
      .expectShutdownObserverRegistration()
      .triggerShutdownObserver()
      .verifySaveActorClosed()
      .expectNoComponentShutdown()
      .completeShutdownFuture()
      .expectComponentShutdown()

  it should "respect the shutdown future on deactivate" in :
    val helper = new ComponentTestHelper

    helper.activate()
      .deactivateAsync()
      .expectNoComponentShutdown()
      .completeShutdownFuture()
      .expectComponentShutdown()

  it should "respect the shutdown timeout on deactivate" in :
    val helper = new ComponentTestHelper
    helper.config.addProperty(PlaylistHandlerConfig.PropShutdownTimeout, "50ms")

    helper.activate()
      .deactivateAsync()
      .expectComponentShutdown(200.millis)

  /**
    * A test helper class that manages an instance under test and its
    * dependencies.
    */
  private class ComponentTestHelper:
    /** The test message bus. */
    val messageBus = new MessageBusTestImpl

    /** The application context used by tests. */
    private val clientApplicationContext = createClientContext()

    /** A promise to control the shutdown future. */
    private val shutdownPromise = Promise[Done]()

    /** Mock for the playlist service. */
    private val playlistService = mock[PlaylistService[Playlist, String]]

    /** Mock for the resolver service. */
    private val resolverService = mock[MediaFileInfoResolverService]

    /** Mock for the playlist save service. */
    private val playlistSaveService = mock[PlaylistSaveService]

    /** Mock for the loader function. */
    private val loaderFunc = mock[PlaylistLoader.LoaderFunc]

    /** Mock for the handle of the loader actor */
    private val loaderActorHandle = mock[AutoCloseable]

    /** Mock for the handle of the save actor. */
    private val saveActorHandle = createSaveActorHandle()

    /**
      * A latch that is used to test the shutdown logic of the component. A
      * shutdown is only possible after the completion of the shutdown future.
      * This helper class provides methods to test whether the component shut
      * down or not in different scenarios based on this latch.
      */
    private val shutdownLatch = new CountDownLatch(1)

    /** The component to be tested. */
    private val component = createComponent()

    /**
      * Counter for the creations of actors during activation. This is used to
      * test whether actors are only created if a valid configuration is
      * available.
      */
    private var actorCreationCount = 0

    /** Stores the shutdown observer that has been registered. */
    private var optShutdownObserver: Option[ShutdownObserver] = None

    /**
      * Allows access to the configuration passed to the test handler. This can
      * be used to tweak certain settings in tests.
      *
      * @return the configuration
      */
    def config: Configuration = clientApplicationContext.managementConfiguration

    /**
      * Activates the test component.
      *
      * @return this test helper
      */
    def activate(): ComponentTestHelper =
      val mockContext = mock[ComponentContext]
      component.initClientContext(clientApplicationContext)
      component.initPlaylistService(playlistService)
      component.initResolverService(resolverService)
      component.activate(mockContext)
      verifyNoInteractions(mockContext)
      this

    /**
      * Deactivates the test component.
      *
      * @return this test helper
      */
    def deactivate(): ComponentTestHelper =
      val mockContext = mock[ComponentContext]
      component.deactivate(mockContext)
      verifyNoInteractions(mockContext)
      this

    /**
      * Calls the test component's _deactivate()_ method in a separate thread
      * and connects its completion with the shutdown latch. This can be used
      * to test whether the OSGi thread is blocked if necessary to wait for a 
      * proper shutdown.
      *
      * @return this test helper
      */
    def deactivateAsync(): ComponentTestHelper =
      Future(deactivate()).andThen(_ => shutdownLatch.countDown())
      this

    /**
      * Checks whether the number of actor creations matches the expected
      * count.
      *
      * @param expectedCount the expected count
      * @return this test helper
      */
    def expectActorCreationCount(expectedCount: Int): ComponentTestHelper =
      actorCreationCount should be(expectedCount)
      this

    /**
      * Tests whether the test component registers itself as a shutdown
      * observer. The observer object is stored, so that interactions with it
      * can be done later.
      *
      * @return this test helper
      */
    def expectShutdownObserverRegistration(): ComponentTestHelper =
      val registration = messageBus.expectMessageType[ShutdownHandler.RegisterShutdownObserver]
      registration.observerID should be(component.componentID)
      optShutdownObserver = Some(registration.observer)
      this

    /**
      * Tests that the test component removes its shutdown observer 
      * registration again.
      *
      * @return this test helper
      */
    def expectShutdownObserverUnregistration(): ComponentTestHelper =
      val unRegistration = messageBus.expectMessageType[ShutdownHandler.RemoveShutdownObserver]
      unRegistration.observerID should be(component.componentID)
      this

    /**
      * Verifies that the handle to the playlist save actor has been closed.
      *
      * @return this test helper
      */
    def verifySaveActorClosed(): ComponentTestHelper =
      verify(saveActorHandle).close()
      this

    /**
      * Verifies that the handles for the actors have been closed.
      *
      * @return this test helper
      */
    def verifyActorsStopped(): ComponentTestHelper =
      verify(loaderActorHandle).close()
      verifySaveActorClosed()

    /**
      * Completes the shutdown future of the handle for the save actor to
      * indicate that this actor is now ready for shutdown.
      *
      * @return this test helper
      */
    def completeShutdownFuture(): ComponentTestHelper =
      shutdownPromise.success(Done)
      this

    /**
      * Waits for a certain timespan for the shutdown latch to be triggered
      * which indicates - dependent on the concrete scenario that is tested -
      * a proper shutdown of the component.
      *
      * @param timeout the timeout to wait
      * @return this test helper
      */
    def expectComponentShutdown(timeout: FiniteDuration = 3.seconds): ComponentTestHelper =
      shutdownLatch.await(timeout.toMillis, TimeUnit.MILLISECONDS) shouldBe true
      this

    /**
      * Tests that the shutdown latch is not triggered for a certain period. 
      * This is used to verify that the component waits for the shutdown future
      * to complete.
      *
      * @return this test helper
      */
    def expectNoComponentShutdown(): ComponentTestHelper =
      shutdownLatch.await(200, TimeUnit.MILLISECONDS) shouldBe false
      this

    /**
      * Invokes the shutdown observer with a notifier object that is connected
      * to the shutdown latch. So it can be tested whether the component
      * correctly notifies it depending on the shutdown future. Before calling
      * this method the shutdown observer must have been obtained.
      *
      * @return this test helper
      */
    def triggerShutdownObserver(): ComponentTestHelper =
      val notifier = new ShutdownHandler.ShutdownCompletionNotifier:
        override def shutdownComplete(): Unit =
          shutdownLatch.countDown()
      optShutdownObserver.value.triggerShutdown(notifier)
      this

    /**
      * Creates the client application context.
      *
      * @return the context
      */
    private def createClientContext(): ClientApplicationContext =
      val actorFactory = mock[ActorFactory]
      val context = mock[ClientApplicationContext]
      when(context.messageBus).thenReturn(messageBus)
      when(context.actorSystem).thenReturn(system)
      when(context.actorFactory).thenReturn(actorFactory)
      when(context.managementConfiguration).thenReturn(createConfig())
      context

    /**
      * Creates the test configuration for the playlist handler component.
      *
      * @return the initialized configuration
      */
    private def createConfig(): Configuration =
      val config = new PropertiesConfiguration
      config.addProperty(PlaylistHandlerConfig.PropPlaylistPath, PathPlaylist.toString)
      config.addProperty(PlaylistHandlerConfig.PropPositionPath, PathPosition.toString)
      config.addProperty(PlaylistHandlerConfig.PropAutoSaveInterval, AutoSaveInterval.toSeconds.toString)
      config.addProperty(PlaylistHandlerConfig.PropMaxFileSize, MaxFileSize)
      config

    /**
      * Creates a mock for the save actor that returns a shutdown future which
      * can be controlled by methods of this test helper class.
      *
      * @return the mock for the handle
      */
    private def createSaveActorHandle(): PlaylistSaveActor.Handle =
      val handle = mock[PlaylistSaveActor.Handle]
      when(handle.shutdownFuture).thenReturn(shutdownPromise.future)
      handle

    /**
      * Returns a stub factory for the loader function which checks the passed
      * in parameters and returns the mock loader function.
      *
      * @return the factory for the loader function
      */
    private def createLoaderFuncFactory(): PlaylistLoader.Factory =
      new PlaylistLoader.Factory:
        override def apply(using system: ActorSystem): LoaderFunc =
          system should be(clientApplicationContext.actorSystem)
          loaderFunc

    /**
      * Returns a stub factory for the save service which checks the parameters
      * passed in and returns the mock save service.
      *
      * @return the factory for the save service
      */
    private def createSaveServiceFactory(): PlaylistSaveService.Factory =
      new PlaylistSaveService.Factory:
        override def apply(plService: PlaylistService[Playlist, String])
                          (using system: ActorSystem): PlaylistSaveService =
          plService should be(playlistService)
          system should be(clientApplicationContext.actorSystem)
          playlistSaveService

    /**
      * Returns a stub factory for the loader actor which checks the parameters
      * passed in and returns the mock loader handle.
      *
      * @return the factory for the loader actor
      */
    private def createLoaderActorFactory(): PlaylistLoaderActor.Factory =
      new PlaylistLoaderActor.Factory:
        override def apply(params: PlaylistLoaderActor.Params)(using actorFactory: ActorFactory): AutoCloseable =
          actorCreationCount += 1
          actorFactory should be(clientApplicationContext.actorFactory)
          params.backoffFactory should be(BackoffActor.newInstance)
          params.messageBus should be(clientApplicationContext.messageBus)
          params.resolverService should be(resolverService)
          params.config.pathPlaylist should be(PathPlaylist)
          params.config.maxFileSize should be(MaxFileSize)
          loaderActorHandle

    /**
      * Returns a stub factory for the save actor which checks the parameters
      * passed in and returns the mock save actor handle.
      *
      * @return the factory for the save service
      */
    private def createSaveActorFactory(): PlaylistSaveActor.Factory =
      new PlaylistSaveActor.Factory:
        override def apply(params: PlaylistSaveActor.Params)
                          (using actorFactory: ActorFactory): PlaylistSaveActor.Handle =
          actorFactory should be(clientApplicationContext.actorFactory)
          params.messageBus should be(clientApplicationContext.messageBus)
          params.playlistSaveService should be(playlistSaveService)
          params.updateService should be(PlaylistWriteStateUpdateServiceImpl)
          params.writeConfig.pathPlaylist should be(PathPlaylist)
          params.writeConfig.pathPosition should be(PathPosition)
          params.writeConfig.autoSaveInterval should be(AutoSaveInterval)
          saveActorHandle

    /**
      * Creates a component to be tested.
      *
      * @return the test component
      */
    private def createComponent(): PlaylistHandlerComponent =
      new PlaylistHandlerComponent(
        saveServiceFactory = createSaveServiceFactory(),
        saveActorFactory = createSaveActorFactory(),
        loaderFuncFactory = createLoaderFuncFactory(),
        loaderActorFactory = createLoaderActorFactory()
      )
