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

package de.oliver_heger.linedj.platform.audio.impl

import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.app.{ClientApplicationContext, ClientApplicationContextImpl}
import de.oliver_heger.linedj.platform.audio.actors.PlayerManagerActor
import de.oliver_heger.linedj.platform.audio.actors.PlayerManagerActor.PlayerManagementCommand
import de.oliver_heger.linedj.platform.audio.playlist.PlaylistMetaDataRegistration
import de.oliver_heger.linedj.platform.audio.{AudioPlayerStateChangeRegistration, AudioPlayerStateChangeUnregistration}
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.comm.ServiceDependencies.{RegisterService, ServiceDependency, UnregisterService}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import de.oliver_heger.linedj.player.engine.PlaybackContextFactory
import de.oliver_heger.linedj.player.engine.facade.AudioPlayer
import de.oliver_heger.linedj.utils.ActorFactory
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.apache.pekko.actor.testkit.typed.scaladsl
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.apache.pekko.actor.{Actor, ActorSystem}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.util.Timeout
import org.mockito.Mockito._
import org.osgi.service.component.ComponentContext
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Success

object AudioPlatformComponentSpec {
  /** Query chunk size for the meta data resolver. */
  private val QueryChunkSize = 42

  /** Size of the meta data cache. */
  private val MetaDataCacheSize = 1200

  /** Timeout for meta data requests. */
  private val MetaDataRequestTimeout = 1.minute

  /**
    * A timeout for the shutdown of the audio player. This is a short duration,
    * since the component waits for this duration until the CloseAck from the
    * management actor is received.
    */
  private val ShutdownTimeout = 100.millis
}

/**
  * Test class for ''AudioPlatformComponent''.
  */
class AudioPlatformComponentSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("AudioPlatformComponentSpec"))

  import AudioPlatformComponentSpec._
  import system.dispatcher

  /** The test kit for testing typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    testKit.shutdownTestKit()
  }

  "An AudioPlatformComponent" should "create a default audio player factory" in {
    val component = new AudioPlatformComponent

    component.playerFactory should not be null
  }

  it should "start a correct management actor" in {
    val factory = mock[PlaybackContextFactory]
    val helper = new ComponentTestHelper

    val managementActor = helper.activate().spawnManagementActor()

    val controllerRegistration = helper.skipMessage().messageBus.findMessageType[RegisterService]
    controllerRegistration should be(RegisterService(ServiceDependency("lineDJ.audioPlayerController")))
    managementActor ! PlayerManagerActor.AddPlaybackContextFactories(List(factory))
    helper.verifyRegisteredPlaybackContextFactories(factory)

    val regMsg = AudioPlayerStateChangeRegistration(ComponentID(), null)
    helper.messageBus.findListenerForMessage(regMsg) should not be None
  }

  it should "register the managed message bus listeners" in {
    val helper = new ComponentTestHelper

    helper.activate().verifyMessageBusRegistration()
  }

  it should "add service registrations for managed objects" in {
    val helper = new ComponentTestHelper

    helper.activate().verifyOsgiServiceRegistrations()
  }

  it should "remove all registrations from the bus on deactivate" in {
    val helper = new ComponentTestHelper

    helper.activate()
      .deactivate()
      .verifyMessageBusRegistrationRemoved()
  }

  it should "remove all service registrations on deactivate" in {
    val helper = new ComponentTestHelper

    helper.activate()
      .deactivate()
      .verifyOsgiServiceDeRegistrations()
  }

  it should "pass a new playback context factory to the management actor" in {
    val factory = mock[PlaybackContextFactory]
    val helper = new ComponentTestHelper

    helper.activate()
      .verifyMetaDataResolverRegistration()
      .playbackContextFactoryRegistered(factory)

    helper.probeManagementActor.expectMessage(PlayerManagerActor.AddPlaybackContextFactories(List(factory)))
  }

  it should "remove an unregistered playback context factory from the management actor" in {
    val factory = mock[PlaybackContextFactory]
    val helper = new ComponentTestHelper

    helper.activate()
      .verifyMetaDataResolverRegistration()
      .playbackContextFactoryRegistered(factory)
      .playbackContextFactoryRemoved(factory)

    helper.probeManagementActor.expectMessageType[PlayerManagerActor.AddPlaybackContextFactories]
    helper.probeManagementActor.expectMessage(PlayerManagerActor.RemovePlaybackContextFactories(List(factory)))
  }

  it should "handle playback context factories before the creation of the player" in {
    val fact1 = mock[PlaybackContextFactory]
    val fact2 = mock[PlaybackContextFactory]
    val factDel = mock[PlaybackContextFactory]
    val fact3 = mock[PlaybackContextFactory]
    val helper = new ComponentTestHelper

    helper.playbackContextFactoryRegistered(fact1)
      .playbackContextFactoryRegistered(fact2)
      .playbackContextFactoryRegistered(factDel)
      .playbackContextFactoryRemoved(factDel)
      .activate()
      .playbackContextFactoryRegistered(fact3)

    helper.probeManagementActor.expectMessage(PlayerManagerActor.AddPlaybackContextFactories(List(fact2, fact1)))
    helper.verifyMetaDataResolverRegistration()
    helper.probeManagementActor.expectMessage(PlayerManagerActor.AddPlaybackContextFactories(List(fact3)))
  }

  it should "close the management actor on deactivation" in {
    val helper = new ComponentTestHelper
    helper.activate()
      .verifyMetaDataResolverRegistration()
    val latch = new CountDownLatch(1)
    val thread = new DeactivateThread(helper, None, latch)

    thread.start()

    val close = helper.probeManagementActor.expectMessageType[PlayerManagerActor.Close]
    close.timeout.duration should be(AudioPlatformComponent.DefaultShutdownTimeout)
    close.client ! PlayerManagerActor.CloseAck(Success(()))
  }

  it should "read the timeout from the configuration when closing the management actor" in {
    val timeout = 333.millis
    val helper = new ComponentTestHelper

    helper.activate()
      .verifyMetaDataResolverRegistration()
      .deactivate(optTimeout = Some(timeout))

    val close = helper.probeManagementActor.expectMessageType[PlayerManagerActor.Close]
    close.timeout.duration should be(timeout)
  }

  it should "only close the management actor if it exists" in {
    val helper = new ComponentTestHelper

    helper.deactivate()
  }

  it should "wait for the CloseAck from the management actor" in {
    val helper = new ComponentTestHelper
    helper.activate()
      .verifyMetaDataResolverRegistration()
    val latch = new CountDownLatch(1)
    val thread = new DeactivateThread(helper, Some(1.second), latch)
    thread.start()
    val managementActor = helper.probeManagementActor
    val close = managementActor.expectMessageType[PlayerManagerActor.Close]

    latch.await(400, TimeUnit.MILLISECONDS) shouldBe false
    close.client ! PlayerManagerActor.CloseAck(Success(()))
    thread.join(1000)
  }

  it should "not wait longer than the timeout when closing the actors involved" in {
    val helper = new ComponentTestHelper
    val latch = new CountDownLatch(1)
    val thread = new DeactivateThread(helper.activate(), Some(100.millis), latch)
    thread.start()

    latch.await(1, TimeUnit.SECONDS) shouldBe true
  }

  it should "create a meta resolver with a specific configuration" in {
    val helper = new ComponentTestHelper
    helper.configuration.addProperty(AudioPlatformComponent.PropMetaDataQueryChunkSize, QueryChunkSize)
    helper.configuration.addProperty(AudioPlatformComponent.PropMetaDataCacheSize, MetaDataCacheSize)
    helper.configuration.addProperty(AudioPlatformComponent.PropMetaDataRequestTimeout,
      MetaDataRequestTimeout.toMillis)

    helper.activate()
      .verifyMetaDataResolverCreation()
  }

  it should "create a meta resolver with the default configuration" in {
    val helper = new ComponentTestHelper

    helper.activate()
      .verifyMetaDataResolverCreation(chunkSize = AudioPlatformComponent.DefaultMetaDataQueryChunkSize,
        cacheSize = AudioPlatformComponent.DefaultMetaDataCacheSize,
        requestTimeout = AudioPlatformComponent.DefaultMetaDataRequestTimeout)
  }

  /**
    * A test helper class managing a test instance and its dependencies.
    */
  private class ComponentTestHelper {
    /** The media manager actor. */
    private val mediaManager = TestProbe().ref

    /** The meta data manager actor. */
    private val metaDataManager = TestProbe().ref

    /** Mock for the audio player. */
    private val audioPlayer = mock[AudioPlayer]

    /** The message bus. */
    val messageBus = new MessageBusTestImpl

    /** Mock for the application configuration. */
    private val appConfig = new PropertiesConfiguration

    /** The test probe representing the management actor. */
    val probeManagementActor: scaladsl.TestProbe[PlayerManagementCommand] =
      testKit.createTestProbe[PlayerManagementCommand]()

    /** The component to be tested. */
    private val component = createComponent()

    /** The meta data resolver instance created by the test component. */
    private var metaDataResolver: PlaylistMetaDataResolver = _

    /** The behavior of the management actor. */
    private var managementActorBehavior: Behavior[PlayerManagementCommand] = _

    /**
      * Activates the test component.
      *
      * @return this test helper
      */
    def activate(): ComponentTestHelper = {
      component.activate(mock[ComponentContext])
      this
    }

    /**
      * Deactivates the test component. It is possible to specify a timeout for
      * closing the audio player. Per default, a CloseAck message is sent to
      * the client that triggers the close of the management actor.
      *
      * @param optTimeout optional timeout for closing the player
      * @return this test helper
      */
    def deactivate(optTimeout: Option[FiniteDuration] = Some(ShutdownTimeout)): ComponentTestHelper = {
      optTimeout.foreach { t =>
        appConfig.addProperty(AudioPlatformComponent.PropShutdownTimeout, t.toMillis)
      }
      component.deactivate(mock[ComponentContext])
      this
    }

    /**
      * Returns the configuration used by the test component.
      *
      * @return the component configuration
      */
    def configuration: Configuration = appConfig

    /**
      * Checks whether the ''PlaylistMetaDataResolver'' has been created
      * correctly.
      *
      * @param chunkSize      the expected query chunk size
      * @param cacheSize      the expected cache size
      * @param requestTimeout the expected request timeout
      * @return this test helper
      */
    def verifyMetaDataResolverCreation(chunkSize: Int = QueryChunkSize,
                                       cacheSize: Int = MetaDataCacheSize,
                                       requestTimeout: Timeout = MetaDataRequestTimeout):
    ComponentTestHelper = {
      metaDataResolver should not be null
      metaDataResolver.metaDataActor should be(mediaManager)
      metaDataResolver.bus should be(messageBus)
      metaDataResolver.ec should be(system.dispatcher)
      metaDataResolver.queryChunkSize should be(chunkSize)
      metaDataResolver.cacheSize should be(cacheSize)
      metaDataResolver.requestTimeout should be(requestTimeout)
      this
    }

    /**
      * Checks that the expected receivers have been correctly registered at
      * the message bus.
      *
      * @return this test helper
      */
    def verifyMessageBusRegistration(): ComponentTestHelper = {
      // One listener for ActorClientSupport, one listener for the metadata service.
      messageBus.currentListeners should have size 2

      val regMsg = PlaylistMetaDataRegistration(ComponentID(), null)
      findReceiverFor(regMsg) should not be None
      this
    }

    /**
      * Checks that all listener registrations at the message bus have been
      * removed.
      *
      * @return this test helper
      */
    def verifyMessageBusRegistrationRemoved(): ComponentTestHelper = {
      messageBus.currentListeners shouldBe empty
      this
    }

    /**
      * Checks whether the controller manager actor was correctly invoked to
      * publish the registration for the metadata resolver service.
      *
      * @return this test helper
      */
    def verifyMetaDataResolverRegistration(): ComponentTestHelper = {
      val expMsg = PlayerManagerActor.PublishAfterCreation(metaDataResolver.playerStateChangeRegistration)
      probeManagementActor.expectMessage(expMsg)
      this
    }

    /**
      * Checks that service registrations are created for the platform
      * services.
      *
      * @return this test helper
      */
    def verifyOsgiServiceRegistrations(): ComponentTestHelper = {
      val serviceDependency = messageBus.expectMessageType[RegisterService].service
      serviceDependency should be(AudioPlatformComponent.PlaylistMetaDataResolverDependency)
      this
    }

    /**
      * Checks that the service registrations for the managed objects have
      * been removed.
      *
      * @return this test helper
      */
    def verifyOsgiServiceDeRegistrations(): ComponentTestHelper = {
      val consumerDeReg = messageBus.findMessageType[AudioPlayerStateChangeUnregistration]
      consumerDeReg.id should be(metaDataResolver.componentID)
      val dependency = messageBus.expectMessageType[UnregisterService].service
      dependency should be(AudioPlatformComponent.PlaylistMetaDataResolverDependency)
      this
    }

    /**
      * Expects that a message was published on the message bus and returns it.
      *
      * @return the message on the message bus
      */
    def nextMessage(): AnyRef =
      messageBus.expectMessageType[AnyRef]

    /**
      * Skips a message that has been published on the message bus.
      *
      * @return this test helper
      */
    def skipMessage(): ComponentTestHelper = {
      nextMessage()
      this
    }

    /**
      * Notifies the test instance about a new playback context factory.
      *
      * @param f the new factory
      * @return this test helper
      */
    def playbackContextFactoryRegistered(f: PlaybackContextFactory): ComponentTestHelper = {
      component addPlaybackContextFactory f
      this
    }

    /**
      * Notifies the test instance about a removed playback context factory.
      *
      * @param f the factory
      * @return this test helper
      */
    def playbackContextFactoryRemoved(f: PlaybackContextFactory): ComponentTestHelper = {
      component removePlaybackContextFactory f
      this
    }

    /**
      * Checks that the specified playback context factories have been passed
      * to the audio player.
      *
      * @param factories the expected factories
      * @return this test helper
      */
    def verifyRegisteredPlaybackContextFactories(factories: PlaybackContextFactory*):
    ComponentTestHelper = {
      factories foreach (f => verify(audioPlayer, timeout(1000)).addPlaybackContextFactory(f))
      this
    }

    /**
      * Starts the management actor based on the provided behavior.
      *
      * @return the reference to the management actor
      */
    def spawnManagementActor(): ActorRef[PlayerManagementCommand] = {
      managementActorBehavior should not be null
      testKit.spawn(managementActorBehavior)
    }

    /**
      * Searches for a message bus listener that can handle the specified
      * message.
      *
      * @param msg the message in question
      * @return an option with the search result
      */
    private def findReceiverFor(msg: Any): Option[Actor.Receive] =
      messageBus.busListeners.find(_.isDefinedAt(msg))

    /**
      * Creates the component to be tested.
      *
      * @return the test component
      */
    private def createComponent(): AudioPlatformComponent = {
      val playerFactory = mock[AudioPlayerFactory]
      val component = new AudioPlatformComponent(playerFactory) {
        override private[impl] def createPlaylistMetaDataResolver(): PlaylistMetaDataResolver = {
          val resolver = super.createPlaylistMetaDataResolver()
          metaDataResolver = resolver
          resolver
        }
      }
      when(playerFactory.createAudioPlayer(appConfig, AudioPlatformComponent.PlayerConfigPrefix,
        mediaManager, component)).thenReturn(Future.successful(audioPlayer))
      component initClientContext createClientContext()
      component initFacadeActors MediaFacadeActors(mediaManager, metaDataManager)
      component
    }

    /**
      * Creates a client application context.
      *
      * @return the context
      */
    private def createClientContext(): ClientApplicationContext =
      new ClientApplicationContextImpl(managementConfiguration = appConfig,
        messageBus = messageBus,
        actorSystem = system,
        actorFactory = createActorFactory())

    /**
      * Creates an actor factory that allows injecting test probes for
      * referenced actors.
      *
      * @return the test actor factory
      */
    private def createActorFactory(): ActorFactory =
      new ActorFactory(system) {
        override def createActor[T](behavior: Behavior[T], name: String, props: Props): ActorRef[T] = {
          name should be(AudioPlatformComponent.AudioPlayerManagementActorName)
          props should be(Props.empty)
          managementActorBehavior = behavior.asInstanceOf[Behavior[PlayerManagementCommand]]
          probeManagementActor.ref.asInstanceOf[ActorRef[T]]
        }
      }
  }

  /**
    * A test thread that deactivates the test component in a separate thread.
    * When deactivation is complete, a latch is triggered. This allows the test
    * case to verify whether the shutdown timeout is taken into account.
    *
    * @param helper     the test helper
    * @param optTimeout the optional timeout to be applied
    * @param latch      the latch to sync with the test case
    */
  private class DeactivateThread(helper: ComponentTestHelper, optTimeout: Option[FiniteDuration],
                                 latch: CountDownLatch) extends Thread {
    override def run(): Unit = {
      helper.deactivate(optTimeout = optTimeout)
      latch.countDown()
    }
  }
}
