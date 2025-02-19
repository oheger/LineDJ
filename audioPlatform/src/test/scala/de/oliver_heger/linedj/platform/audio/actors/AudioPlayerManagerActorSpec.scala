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

package de.oliver_heger.linedj.platform.audio.actors

import de.oliver_heger.linedj.ActorTestKitSupport
import de.oliver_heger.linedj.io.CloseAck
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.audio.actors.PlayerManagerActor.{AddAudioStreamFactories, AddPlaybackContextFactories, PlayerManagementCommand, RemoveAudioStreamFactories, RemovePlaybackContextFactories}
import de.oliver_heger.linedj.platform.audio.{AudioPlayerState, AudioPlayerStateChangedEvent}
import de.oliver_heger.linedj.platform.comm.ServiceDependencies.{RegisterService, UnregisterService}
import de.oliver_heger.linedj.player.engine.facade.AudioPlayer
import de.oliver_heger.linedj.player.engine.{AudioSource, AudioSourceStartedEvent, AudioStreamFactory, PlaybackContextFactory, PlayerEvent}
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.Timeout
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.verification.VerificationMode
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.immutable.Seq
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

object AudioPlayerManagerActorSpec:
  /**
    * The default verification mode for verify calls. ''timeout'' is used here
    * to deal with asynchronous operations.
    */
  private val DefaultVerificationMode = timeout(3000)

  /** The timeout when closing the player. */
  private implicit val CloseTimeout: Timeout = Timeout(10.seconds)

/**
  * Test class for [[AudioPlayerManagerActor]].
  */
class AudioPlayerManagerActorSpec extends AnyFlatSpec with Matchers with MockitoSugar with ActorTestKitSupport:

  import AudioPlayerManagerActorSpec.*

  /**
    * Creates a number of mock [[PlaybackContextFactory]] objects.
    *
    * @param count the number of factories to create
    * @return a list with the mock factories
    */
  private def createPlaybackContextFactories(count: Int): List[PlaybackContextFactory] =
    (1 to count).map(_ => mock[PlaybackContextFactory]).toList

  /**
    * Creates a number of mock [[AudioStreamFactory]] objects.
    *
    * @param count the number of factories to create
    * @return a list with the mock factories
    */
  private def createAudioStreamFactories(count: Int): List[AudioStreamFactory] =
    (1 to count).map(_ => mock[AudioStreamFactory]).toList

  "AudioPlayerManagerActor" should "manage playback context factories received before the player creation" in :
    val factories = createPlaybackContextFactories(4)
    val helper = new ManagerActorTestHelper

    helper.sendCommand(AddPlaybackContextFactories(factories))
      .controllerCreated()

    factories foreach { factory =>
      helper.verifyPlaybackContextFactoryAdded(factory)
    }

  it should "manage audio stream factories received before the player creation" in :
    val factories = createAudioStreamFactories(4)
    val helper = new ManagerActorTestHelper

    helper.sendCommand(AddAudioStreamFactories(factories))
      .controllerCreated()

    factories foreach { factory =>
      helper.verifyAudioStreamFactoryAdded(factory)
    }

  it should "support removing playback context factories before the player creation" in :
    val factories = createPlaybackContextFactories(8)
    val removedFactories = List(factories(2), factories(5))
    val helper = new ManagerActorTestHelper

    helper.sendCommand(AddPlaybackContextFactories(factories))
      .sendCommand(RemovePlaybackContextFactories(removedFactories))
      .controllerCreated()

    helper.verifyPlaybackContextFactoryAdded(factories.head)
      .verifyPlaybackContextFactoryRemoved(removedFactories.head, never())
    removedFactories foreach { factory =>
      helper.verifyPlaybackContextFactoryAdded(factory, never())
    }

  it should "support removing audio stream factories before the player creation" in :
    val factories = createAudioStreamFactories(8)
    val removedFactories = List(factories(2), factories(5))
    val helper = new ManagerActorTestHelper

    helper.sendCommand(AddAudioStreamFactories(factories))
      .sendCommand(RemoveAudioStreamFactories(removedFactories))
      .controllerCreated()

    helper.verifyAudioStreamFactoryAdded(factories.head)
      .verifyAudioStreamFactoryRemoved(removedFactories.head, never())
    removedFactories foreach { factory =>
      helper.verifyAudioStreamFactoryAdded(factory, never())
    }

  it should "register the player controller when it is created" in :
    val helper = new ManagerActorTestHelper

    helper.controllerCreated()
      .checkControllerRegistration()

  it should "support adding playback context factories after the player creation" in :
    val factories = createPlaybackContextFactories(2)
    val helper = new ManagerActorTestHelper

    helper.controllerCreated()
      .checkControllerRegistration()
      .sendCommand(AddPlaybackContextFactories(factories))

    factories foreach { factory =>
      helper.verifyPlaybackContextFactoryAdded(factory)
    }

  it should "support adding audio stream factories after the player creation" in :
    val factories = createAudioStreamFactories(2)
    val helper = new ManagerActorTestHelper

    helper.controllerCreated()
      .checkControllerRegistration()
      .sendCommand(AddAudioStreamFactories(factories))

    factories foreach { factory =>
      helper.verifyAudioStreamFactoryAdded(factory)
    }

  it should "support removing playback context factories after the player creation" in :
    val factories = createPlaybackContextFactories(2)
    val helper = new ManagerActorTestHelper

    helper.controllerCreated()
      .checkControllerRegistration()
      .sendCommand(RemovePlaybackContextFactories(factories))

    factories foreach { factory =>
      helper.verifyPlaybackContextFactoryRemoved(factory)
    }

  it should "support audio stream factories after the player creation" in :
    val factories = createAudioStreamFactories(2)
    val helper = new ManagerActorTestHelper

    helper.controllerCreated()
      .checkControllerRegistration()
      .sendCommand(RemoveAudioStreamFactories(factories))

    factories foreach { factory =>
      helper.verifyAudioStreamFactoryRemoved(factory)
    }

  it should "register an event actor at the audio player" in :
    val helper = new ManagerActorTestHelper

    val eventListener = helper.controllerCreated()
      .checkControllerRegistration()
      .fetchEventListenerActor()

    val event = AudioSourceStartedEvent(AudioSource("test.mp3", 2048, 0, 0))
    eventListener ! event
    helper.messageBus.expectMessageType[PlayerEvent] should be(event)

  it should "remove registrations when closing the player" in :
    val closeResult = Promise[Seq[CloseAck]]()
    val helper = new ManagerActorTestHelper

    val probeClient = helper.controllerCreated()
      .checkControllerRegistration()
      .prepareClosing(closeResult.future)
      .closePlayer()

    helper.checkControllerDeRegistration()
      .verifyEventListenerRemoved()
    probeClient.expectNoMessage(100.millis)

  it should "send an Ack message when the player has been closed successfully" in :
    val closeResult = Future.successful(Seq.empty[CloseAck])
    val helper = new ManagerActorTestHelper

    val probeClient = helper.controllerCreated()
      .checkControllerRegistration()
      .prepareClosing(closeResult)
      .closePlayer()

    probeClient.expectMessage(PlayerManagerActor.CloseAck(Success(())))
    helper.checkActorStopped()

  it should "send an Ack message when the player has been closed with a failure" in :
    val exception = new IllegalStateException("Test exception: Could not close audio player.")
    val closeResult = Future.failed[Seq[CloseAck]](exception)
    val helper = new ManagerActorTestHelper

    val probeClient = helper.controllerCreated()
      .checkControllerRegistration()
      .prepareClosing(closeResult)
      .closePlayer()

    val ack = probeClient.expectMessageType[PlayerManagerActor.CloseAck]
    ack.result should be(Failure(exception))
    helper.checkActorStopped()

  it should "handle a close command before the player has been created" in :
    val helper = new ManagerActorTestHelper

    val probeClient = helper.prepareClosing(Future.successful(Seq.empty))
      .closePlayer()
    helper.controllerCreated()

    probeClient.expectMessage(PlayerManagerActor.CloseAck(Success(())))
    helper.messageBus.expectNoMessage(200.millis)
    helper.checkActorStopped()

  it should "handle a failed creation of the controller" in :
    val exception = new IllegalStateException("Test exception: Could not create audio controller.")
    val helper = new ManagerActorTestHelper

    val probeClient = helper.controllerCreationFailed(exception)
      .closePlayer()

    val ack = probeClient.expectMessageType[PlayerManagerActor.CloseAck]
    ack.result should be(Failure(exception))
    helper.messageBus.expectNoMessage(200.millis)
    helper.checkActorStopped()

  it should "handle a close command before the creation of the player failed" in :
    val exception = new IllegalStateException("Test exception: Could not create audio controller.")
    val helper = new ManagerActorTestHelper

    val probeClient = helper.closePlayer()
    helper.controllerCreationFailed(exception)

    val ack = probeClient.expectMessageType[PlayerManagerActor.CloseAck]
    ack.result should be(Failure(exception))
    helper.messageBus.expectNoMessage(200.millis)
    helper.checkActorStopped()

  it should "publish a message after the audio controller has been registered" in :
    val message = AudioPlayerStateChangedEvent(AudioPlayerState.Initial)
    val helper = new ManagerActorTestHelper

    helper.controllerCreated()
      .checkControllerRegistration()
      .sendCommand(PlayerManagerActor.PublishAfterCreation(message))

    helper.messageBus.findMessageType[AudioPlayerStateChangedEvent] should be(message)

  it should "collect messages to be published until the audio controller has been created" in :
    val message1 = AudioPlayerStateChangedEvent(AudioPlayerState.Initial)
    val message2 = AudioPlayerStateChangedEvent(AudioPlayerState.Initial.copy(playlistSeqNo = 42))
    val helper = new ManagerActorTestHelper

    helper.sendCommand(PlayerManagerActor.PublishAfterCreation(message1))
      .sendCommand(PlayerManagerActor.PublishAfterCreation(message2))
      .controllerCreated()

    helper.messageBus.findMessageType[AudioPlayerStateChangedEvent] should be(message1)
    helper.messageBus.expectMessageType[AudioPlayerStateChangedEvent] should be(message2)
    helper.messageBus.expectNoMessage(200.millis)

  /**
    * A test helper class managing the dependencies of an actor under test.
    */
  private class ManagerActorTestHelper:
    /** A promise to complete the creation of a controller. */
    private val playerPromise = Promise[AudioPlayerController]()

    /** A mock for the audio player. */
    private val player = mock[AudioPlayer]

    /** A mock for the audio player controller. */
    private val controller = createControllerMock()

    /** A stub for the message bus. */
    val messageBus = new MessageBusTestImpl()

    /** The actor to be tested. */
    private val managerActor = createManagerActor()

    /**
      * Completes the successful creation of the controller.
      *
      * @return this test helper
      */
    def controllerCreated(): ManagerActorTestHelper =
      playerPromise.success(controller)
      this

    /**
      * Completes the ''Promise'' for the controller creation with the given
      * exception.
      *
      * @param exception the exception causing the creation to fail
      * @return this test helper
      */
    def controllerCreationFailed(exception: Throwable): ManagerActorTestHelper =
      playerPromise.failure(exception)
      this

    /**
      * Sends the given command to the actor under test.
      *
      * @param command the command to send
      * @return this test helper
      */
    def sendCommand(command: PlayerManagementCommand): ManagerActorTestHelper =
      managerActor ! command
      this

    /**
      * Verifies that the given [[PlaybackContextFactory]] was added to the
      * managed player.
      *
      * @param factory the factory to check
      * @param mode    the verification mode
      * @return this test helper
      */
    def verifyPlaybackContextFactoryAdded(factory: PlaybackContextFactory,
                                          mode: VerificationMode = DefaultVerificationMode): ManagerActorTestHelper =
      verify(player, mode).addPlaybackContextFactory(factory)
      this

    /**
      * Verifies that the given [[PlaybackContextFactory]] was removed from the
      * managed player.
      *
      * @param factory the factory to check
      * @param mode    the verification mode
      * @return this test helper
      */
    def verifyPlaybackContextFactoryRemoved(factory: PlaybackContextFactory,
                                            mode: VerificationMode = DefaultVerificationMode): ManagerActorTestHelper =
      verify(player, mode).removePlaybackContextFactory(factory)
      this

    /**
      * Verifies that the given [[AudioStreamFactory]] was added to the managed
      * player.
      *
      * @param factory the factory to check
      * @param mode    the verification mode
      * @return this test helper
      */
    def verifyAudioStreamFactoryAdded(factory: AudioStreamFactory,
                                      mode: VerificationMode = DefaultVerificationMode): ManagerActorTestHelper =
      verify(player, mode).addAudioStreamFactory(factory)
      this

    /**
      * Verifies that the given [[AudioStreamFactory]] was removed from the
      * managed player.
      *
      * @param factory the factory to check
      * @param mode    the verification mode
      * @return this test helper
      */
    def verifyAudioStreamFactoryRemoved(factory: AudioStreamFactory,
                                        mode: VerificationMode = DefaultVerificationMode): ManagerActorTestHelper =
      verify(player, mode).removeAudioStreamFactory(factory)
      this

    /**
      * Checks whether the player controller has been correctly registered at
      * the message bus.
      *
      * @return this test helper
      */
    def checkControllerRegistration(): ManagerActorTestHelper =
      val regMsg = messageBus.expectMessageType[RegisterService]
      regMsg.service.serviceName should be("lineDJ.audioPlayerController")
      messageBus.currentListeners should have size 1
      // Need to wait for event listener registration; otherwise Mockito's stubbing gets messed up.
      fetchEventListenerActor()
      this

    /**
      * Checks whether all registration steps are reverted on closing the
      * player.
      *
      * @return this test helper
      */
    def checkControllerDeRegistration(): ManagerActorTestHelper =
      val unRegMsg = messageBus.expectMessageType[UnregisterService]
      unRegMsg.service.serviceName should be("lineDJ.audioPlayerController")
      messageBus.currentListeners shouldBe empty
      this

    /**
      * Obtains the event actor that has been registered at the player.
      *
      * @return the event actor
      */
    def fetchEventListenerActor(): ActorRef[PlayerEvent] =
      val capture = ArgumentCaptor.forClass(classOf[ActorRef[PlayerEvent]])
      verify(player, DefaultVerificationMode).addEventListener(capture.capture())
      capture.getValue

    /**
      * Verifies that the event listener was correctly removed at the player.
      *
      * @return this test helper
      */
    def verifyEventListenerRemoved(): ManagerActorTestHelper =
      val listener = fetchEventListenerActor()
      verify(player, DefaultVerificationMode).removeEventListener(listener)
      this

    /**
      * Prepares the mock for the audio player to expect a ''close()'' call and
      * sets the result for this call.
      *
      * @param result the ''Future'' to be returned by close()
      * @return this test helper
      */
    def prepareClosing(result: Future[Seq[CloseAck]]): ManagerActorTestHelper =
      when(player.close()(any(), any())).thenReturn(result)
      this

    /**
      * Sends a ''Close'' command to the test actor. Returns the test probe
      * that was passed as client.
      *
      * @return the test probe that should receive the ''CloseAck''
      */
    def closePlayer(): TestProbe[PlayerManagerActor.CloseAck] =
      val probe = testKit.createTestProbe[PlayerManagerActor.CloseAck]()
      managerActor ! PlayerManagerActor.Close(probe.ref, CloseTimeout)
      probe

    /**
      * Checks whether the actor under test has stopped itself.
      *
      * @return this test helper
      */
    def checkActorStopped(): ManagerActorTestHelper =
      val probe = testKit.createTestProbe()
      probe.expectTerminated(managerActor)
      this

    /**
      * Creates a mock for an [[AudioPlayerController]].
      *
      * @return the mock for the controller
      */
    private def createControllerMock(): AudioPlayerController =
      new AudioPlayerController(player, messageBus)

    /**
      * Creates an instance of the actor to be tested.
      *
      * @return the test actor instance
      */
    private def createManagerActor(): ActorRef[PlayerManagementCommand] =
      implicit val ec: ExecutionContext = testKit.system.executionContext
      val creator: AudioPlayerManagerActor.ControllerCreationFunc = () => playerPromise.future
      testKit.spawn(AudioPlayerManagerActor(messageBus)(creator))
