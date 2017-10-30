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

package de.oliver_heger.linedj.platform.audio.impl

import java.util.concurrent.{CountDownLatch, TimeUnit}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.io.CloseAck
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.platform.comm.ServiceDependencies.{RegisterService, UnregisterService}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import de.oliver_heger.linedj.player.engine.PlaybackContextFactory
import de.oliver_heger.linedj.player.engine.facade.AudioPlayer
import org.apache.commons.configuration.PropertiesConfiguration
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.osgi.service.component.ComponentContext
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

/**
  * Test class for ''AudioPlatformComponent''.
  */
class AudioPlatformComponentSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("AudioPlatformComponentSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "An AudioPlatformComponent" should "create a default audio player factory" in {
    val component = new AudioPlatformComponent

    component.playerFactory should not be null
  }

  it should "create an audio player controller" in {
    val helper = new ComponentTestHelper

    helper.activate().verifyAudioControllerCreation()
  }

  it should "register the audio player controller as message bus listener" in {
    val helper = new ComponentTestHelper

    helper.activate().verifyMessageBusRegistration()
  }

  it should "add a service registration for the player controller" in {
    val helper = new ComponentTestHelper

    helper.activate().verifyPlayerControllerServiceRegistration()
  }

  it should "remove the audio player controller registration from the bus" in {
    val helper = new ComponentTestHelper

    helper.activate()
      .deactivate()
      .verifyMessageBusRegistrationRemoved()
  }

  it should "remove the service registration for the player controller" in {
    val helper = new ComponentTestHelper

    helper.activate().skipMessage()
      .deactivate()
      .verifyPlayerControllerServiceDeRegistration()
  }

  it should "pass a new playback context factory to the player" in {
    val factory = mock[PlaybackContextFactory]
    val helper = new ComponentTestHelper

    helper.activate()
      .playbackContextFactoryRegistered(factory)
      .verifyRegisteredPlaybackContextFactories(factory)
  }

  it should "remove an unregistered playback context factory from the player" in {
    val factory = mock[PlaybackContextFactory]
    val helper = new ComponentTestHelper

    helper.activate()
      .playbackContextFactoryRegistered(factory)
      .playbackContextFactoryRemoved(factory)
      .verifyRemovedPlaybackContextFactories(factory)
  }

  it should "handle playback context factories before the creation of the player" in {
    val fact1 = mock[PlaybackContextFactory]
    val fact2 = mock[PlaybackContextFactory]
    val fact3 = mock[PlaybackContextFactory]
    val helper = new ComponentTestHelper

    helper.playbackContextFactoryRegistered(fact1)
      .playbackContextFactoryRegistered(fact2)
      .playbackContextFactoryRemoved(fact2)
      .activate()
      .playbackContextFactoryRegistered(fact3)
      .verifyRegisteredPlaybackContextFactories(fact1, fact3)
      .verifyPlaybackContextFactoryNotRegistered(fact2)
  }

  it should "close the audio player on deactivation" in {
    val helper = new ComponentTestHelper

    helper.activate()
      .deactivate()
      .verifyPlayerClosed()
  }

  it should "read the timeout from the configuration when closing the audio player" in {
    val timeout = 333.millis
    val helper = new ComponentTestHelper

    helper.activate()
      .deactivate(optTimeout = Some(timeout))
      .verifyPlayerClosed(timeout = timeout)
  }

  it should "only close the audio player if it exists" in {
    val helper = new ComponentTestHelper

    helper.deactivate()
  }

  it should "wait for actors involved to be closed" in {
    val helper = new ComponentTestHelper
    val latch = new CountDownLatch(1)
    val thread = new DeactivateThread(helper.activate(), 1.minute, latch)
    thread.start()

    latch.await(500, TimeUnit.MILLISECONDS) shouldBe false
  }

  it should "not wait longer than the timeout when closing the actors involved" in {
    val helper = new ComponentTestHelper
    val latch = new CountDownLatch(1)
    val thread = new DeactivateThread(helper.activate(), 100.millis, latch)
    thread.start()

    latch.await(1, TimeUnit.SECONDS) shouldBe true
  }

  it should "register an event sink at the audio player" in {
    val helper = new ComponentTestHelper

    val sink = helper.activate().verifyAndFetchEventSinkRegistration()
    implicit val mat: ActorMaterializer = ActorMaterializer()
    val Message = new Object
    Source.single(Message).runWith(sink)
    helper.skipMessage().nextMessage() should be(Message)
  }

  it should "remove the event sink from the audio player on deactivation" in {
    val helper = new ComponentTestHelper

    helper.activate()
      .deactivate()
      .verifyEventSinkRemoved()
  }

  /**
    * A test helper class managing a test instance and its dependencies.
    */
  private class ComponentTestHelper {
    /** The ID to be returned for an event sink registration. */
    private val SinkRegistrationID = 20171030

    /** The media manager actor. */
    private val mediaManager = TestProbe().ref

    /** Mock for the audio player. */
    private val audioPlayer = createPlayerMock()

    /** The message bus. */
    private val messageBus = new MessageBusTestImpl

    /** Mock for the application configuration. */
    private val appConfig = new PropertiesConfiguration

    /** The component to be tested. */
    private val component = createComponent()

    /** The controller instance created by the test component. */
    private var audioController: AudioPlayerController = _

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
      * closing the audio player and the future result to be returned by the
      * player's close() method.
      *
      * @param optTimeout  optional timeout for closing the player
      * @param closeResult the result of the close operation
      * @return this test helper
      */
    def deactivate(optTimeout: Option[FiniteDuration] = None,
                   closeResult: Future[Seq[CloseAck]] =
                   Future.successful(List(CloseAck(mediaManager)))): ComponentTestHelper = {
      optTimeout.foreach { t =>
        appConfig.addProperty(AudioPlatformComponent.PropShutdownTimeout, t.toMillis)
      }
      when(audioPlayer.close()(any(), any())).thenReturn(closeResult)
      component.deactivate(mock[ComponentContext])
      this
    }

    /**
      * Checks that a correct audio controller instance has been created.
      *
      * @return this test helper
      */
    def verifyAudioControllerCreation(): ComponentTestHelper = {
      audioController should not be null
      audioController.player should be(audioPlayer)
      this
    }

    /**
      * Checks that the audio controller has been correctly registered at the
      * message bus.
      *
      * @return this test helper
      */
    def verifyMessageBusRegistration(): ComponentTestHelper = {
      // unfortunately, the registered function cannot be checked because
      // AudioPlayerController.receive is final
      messageBus.currentListeners should have size 1
      this
    }

    /**
      * Checks that all listener registrations at the message bus have been
      * removed.
      *
      * @return this test helper
      */
    def verifyMessageBusRegistrationRemoved(): ComponentTestHelper = {
      messageBus.currentListeners shouldBe 'empty
      this
    }

    /**
      * Checks that a service registration is created for the audio player
      * controller.
      *
      * @return this test helper
      */
    def verifyPlayerControllerServiceRegistration(): ComponentTestHelper = {
      val reg = messageBus.expectMessageType[RegisterService]
      reg.service.serviceName should be(AudioPlatformComponent.PlayerControllerServiceName)
      this
    }

    /**
      * Checks that the service registration corresponding to the audio
      * player controller has been removed.
      *
      * @return this test helper
      */
    def verifyPlayerControllerServiceDeRegistration(): ComponentTestHelper = {
      val unReg = messageBus.expectMessageType[UnregisterService]
      unReg.service.serviceName should be(AudioPlatformComponent.PlayerControllerServiceName)
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
      factories foreach (f => verify(audioPlayer).addPlaybackContextFactory(f))
      this
    }

    /**
      * Checks that the specified playback context factories have been removed
      * from the audio player.
      *
      * @param factories the expected factories
      * @return this test helper
      */
    def verifyRemovedPlaybackContextFactories(factories: PlaybackContextFactory*):
    ComponentTestHelper = {
      factories foreach (f => verify(audioPlayer).removePlaybackContextFactory(f))
      this
    }

    /**
      * Checks that the specified playback context factory has not been added
      * to the audio player.
      *
      * @param factory the factory in question
      * @return this test helper
      */
    def verifyPlaybackContextFactoryNotRegistered(factory: PlaybackContextFactory):
    ComponentTestHelper = {
      verify(audioPlayer, never()).addPlaybackContextFactory(factory)
      this
    }

    /**
      * Verifies that a registration for an event sink was added to the
      * audio player. The sink is returned.
      *
      * @return the event sink registered at the player
      */
    def verifyAndFetchEventSinkRegistration(): Sink[Any, Any] = {
      val captor = ArgumentCaptor.forClass(classOf[Sink[Any, Any]])
      verify(audioPlayer).registerEventSink(captor.capture())
      captor.getValue
    }

    /**
      * Checks that the event sink is removed again when the component is
      * deactivated.
      *
      * @return this test helper
      */
    def verifyEventSinkRemoved(): ComponentTestHelper = {
      verify(audioPlayer).removeEventSink(SinkRegistrationID)
      this
    }

    /**
      * Verifies that the audio player has been closed correctly.
      *
      * @param timeout the expected timeout for the close operation
      * @return this test helper
      */
    def verifyPlayerClosed(timeout: FiniteDuration =
                           AudioPlatformComponent.DefaultShutdownTimeout): ComponentTestHelper = {
      verify(audioPlayer).close()(ec = system.dispatcher, timeout = Timeout(timeout))
      this
    }

    /**
      * Creates the component to be tested.
      *
      * @return the test component
      */
    private def createComponent(): AudioPlatformComponent = {
      val playerFactory = mock[AudioPlayerFactory]
      val component = new AudioPlatformComponent(playerFactory) {
        override private[impl] def createPlayerController(): AudioPlayerController = {
          val ctrl = super.createPlayerController()
          audioController = ctrl
          ctrl
        }
      }
      when(playerFactory.createAudioPlayer(appConfig, AudioPlatformComponent.PlayerConfigPrefix,
        mediaManager, component)).thenReturn(audioPlayer)
      component initClientContext createClientContext()
      component initFacadeActors MediaFacadeActors(mediaManager, null)
      component
    }

    /**
      * Creates a client application context.
      *
      * @return the context
      */
    private def createClientContext(): ClientApplicationContext = {
      val context = mock[ClientApplicationContext]
      when(context.messageBus).thenReturn(messageBus)
      when(context.managementConfiguration).thenReturn(appConfig)
      when(context.actorSystem).thenReturn(system)
      context
    }

    /**
      * Creates a mock for the audio player.
      *
      * @return the mock for the audio player
      */
    private def createPlayerMock(): AudioPlayer = {
      val p = mock[AudioPlayer]
      when(p.registerEventSink(any())).thenReturn(SinkRegistrationID)
      p
    }
  }

  /**
    * A test thread that deactivates the test component in a separate thread.
    * When deactivation is complete, a latch is triggered. This allows the test
    * case to verify whether the shutdown timeout is taken into account.
    *
    * @param helper  the test helper
    * @param timeout the timeout to be applied
    * @param latch   the latch to sync with the test case
    */
  private class DeactivateThread(helper: ComponentTestHelper, timeout: FiniteDuration,
                                 latch: CountDownLatch) extends Thread {
    override def run(): Unit = {
      val closeResult = Promise[Seq[CloseAck]]()
      helper.deactivate(closeResult = closeResult.future, optTimeout = Some(timeout))
      latch.countDown()
    }
  }

}
