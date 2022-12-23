/*
 * Copyright 2015-2022 The Developers Team.
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

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.util.Timeout
import de.oliver_heger.linedj.io.CloseAck
import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.audio.actors.AudioPlayerManagerActor.{AddPlaybackContextFactories, AudioPlayerManagementCommand, RemovePlaybackContextFactories}
import de.oliver_heger.linedj.platform.audio.{AudioPlayerState, AudioPlayerStateChangedEvent}
import de.oliver_heger.linedj.platform.comm.ServiceDependencies.{RegisterService, UnregisterService}
import de.oliver_heger.linedj.player.engine.facade.AudioPlayer
import de.oliver_heger.linedj.player.engine.{AudioSource, AudioSourceStartedEvent, PlaybackContextFactory, PlayerEvent}
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._
import org.mockito.verification.VerificationMode
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

object AudioPlayerManagerActorSpec {
  /**
    * The default verification mode for verify calls. ''timeout'' is used here
    * to deal with asynchronous operations.
    */
  private val DefaultVerificationMode = timeout(3000)

  /** The timeout when closing the player. */
  private implicit val CloseTimeout: Timeout = Timeout(10.seconds)
}

/**
  * Test class for [[AudioPlayerManagerActor]].
  */
class AudioPlayerManagerActorSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with MockitoSugar {

  import AudioPlayerManagerActorSpec._

  /**
    * Creates a number of mock factories.
    *
    * @param count the number of factories to create
    * @return a list with the mock factories
    */
  private def createFactories(count: Int): List[PlaybackContextFactory] =
    (1 to count).map(_ => mock[PlaybackContextFactory]).toList

  "AudioPlayerManagerActor" should "manage factories received before the player creation" in {
    val factories = createFactories(4)
    val helper = new ManagerActorTestHelper

    helper.sendCommand(AddPlaybackContextFactories(factories))
      .controllerCreated()

    factories foreach { factory =>
      helper.verifyFactoryAdded(factory)
    }
  }

  it should "support removing factories before the player creation" in {
    val factories = createFactories(8)
    val removedFactories = List(factories(2), factories(5))
    val helper = new ManagerActorTestHelper

    helper.sendCommand(AddPlaybackContextFactories(factories))
      .sendCommand(RemovePlaybackContextFactories(removedFactories))
      .controllerCreated()

    helper.verifyFactoryAdded(factories.head)
      .verifyFactoryRemoved(removedFactories.head, never())
    removedFactories foreach { factory =>
      helper.verifyFactoryAdded(factory, never())
    }
  }

  it should "register the player controller when it is created" in {
    val helper = new ManagerActorTestHelper

    helper.controllerCreated()
      .checkControllerRegistration()
  }

  it should "support adding factories after the player creation" in {
    val factories = createFactories(2)
    val helper = new ManagerActorTestHelper

    helper.controllerCreated()
      .checkControllerRegistration()
      .sendCommand(AddPlaybackContextFactories(factories))

    factories foreach { factory =>
      helper.verifyFactoryAdded(factory)
    }
  }

  it should "support removing factories after the player creation" in {
    val factories = createFactories(2)
    val helper = new ManagerActorTestHelper

    helper.controllerCreated()
      .checkControllerRegistration()
      .sendCommand(RemovePlaybackContextFactories(factories))

    factories foreach { factory =>
      helper.verifyFactoryRemoved(factory)
    }
  }

  it should "register an event actor at the audio player" in {
    val helper = new ManagerActorTestHelper

    val eventListener = helper.controllerCreated()
      .checkControllerRegistration()
      .fetchEventListenerActor()

    val event = AudioSourceStartedEvent(AudioSource("test.mp3", 2048, 0, 0))
    eventListener ! event
    helper.messageBus.expectMessageType[PlayerEvent] should be(event)
  }

  it should "remove registrations when closing the player" in {
    val closeResult = Promise[Seq[CloseAck]]()
    val helper = new ManagerActorTestHelper

    val probeClient = helper.controllerCreated()
      .checkControllerRegistration()
      .prepareClosing(closeResult.future)
      .closePlayer()

    helper.checkControllerDeRegistration()
      .verifyEventListenerRemoved()
    probeClient.expectNoMessage(100.millis)
  }

  it should "send an Ack message when the player has been closed successfully" in {
    val closeResult = Future.successful(Seq.empty[CloseAck])
    val helper = new ManagerActorTestHelper

    val probeClient = helper.controllerCreated()
      .checkControllerRegistration()
      .prepareClosing(closeResult)
      .closePlayer()

    probeClient.expectMessage(AudioPlayerManagerActor.CloseAck(Success(())))
    helper.checkActorStopped()
  }

  it should "send an Ack message when the player has been closed with a failure" in {
    val exception = new IllegalStateException("Test exception: Could not close audio player.")
    val closeResult = Future.failed[Seq[CloseAck]](exception)
    val helper = new ManagerActorTestHelper

    val probeClient = helper.controllerCreated()
      .checkControllerRegistration()
      .prepareClosing(closeResult)
      .closePlayer()

    val ack = probeClient.expectMessageType[AudioPlayerManagerActor.CloseAck]
    ack.result should be(Failure(exception))
    helper.checkActorStopped()
  }

  it should "handle a close command before the player has been created" in {
    val helper = new ManagerActorTestHelper

    val probeClient = helper.prepareClosing(Future.successful(Seq.empty))
      .closePlayer()
    helper.controllerCreated()

    probeClient.expectMessage(AudioPlayerManagerActor.CloseAck(Success(())))
    helper.messageBus.expectNoMessage(200.millis)
    helper.checkActorStopped()
  }

  it should "handle a failed creation of the controller" in {
    val exception = new IllegalStateException("Test exception: Could not create audio controller.")
    val helper = new ManagerActorTestHelper

    val probeClient = helper.controllerCreationFailed(exception)
      .closePlayer()

    val ack = probeClient.expectMessageType[AudioPlayerManagerActor.CloseAck]
    ack.result should be(Failure(exception))
    helper.messageBus.expectNoMessage(200.millis)
    helper.checkActorStopped()
  }

  it should "handle a close command before the creation of the player failed" in {
    val exception = new IllegalStateException("Test exception: Could not create audio controller.")
    val helper = new ManagerActorTestHelper

    val probeClient = helper.closePlayer()
    helper.controllerCreationFailed(exception)

    val ack = probeClient.expectMessageType[AudioPlayerManagerActor.CloseAck]
    ack.result should be(Failure(exception))
    helper.messageBus.expectNoMessage(200.millis)
    helper.checkActorStopped()
  }

  it should "publish a message after the audio controller has been registered" in {
    val message = AudioPlayerStateChangedEvent(AudioPlayerState.Initial)
    val helper = new ManagerActorTestHelper

    helper.controllerCreated()
      .checkControllerRegistration()
      .sendCommand(AudioPlayerManagerActor.PublishToController(message))

    helper.messageBus.findMessageType[AudioPlayerStateChangedEvent] should be(message)
  }

  it should "collect messages to be published until the audio controller has been created" in {
    val message1 = AudioPlayerStateChangedEvent(AudioPlayerState.Initial)
    val message2 = AudioPlayerStateChangedEvent(AudioPlayerState.Initial.copy(playlistSeqNo = 42))
    val helper = new ManagerActorTestHelper

    helper.sendCommand(AudioPlayerManagerActor.PublishToController(message1))
      .sendCommand(AudioPlayerManagerActor.PublishToController(message2))
      .controllerCreated()

    helper.messageBus.findMessageType[AudioPlayerStateChangedEvent] should be(message1)
    helper.messageBus.expectMessageType[AudioPlayerStateChangedEvent] should be(message2)
    helper.messageBus.expectNoMessage(200.millis)
  }

  /**
    * A test helper class managing the dependencies of an actor under test.
    */
  private class ManagerActorTestHelper {
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
    def controllerCreated(): ManagerActorTestHelper = {
      playerPromise.success(controller)
      this
    }

    /**
      * Completes the ''Promise'' for the controller creation with the given
      * exception.
      *
      * @param exception the exception causing the creation to fail
      * @return this test helper
      */
    def controllerCreationFailed(exception: Throwable): ManagerActorTestHelper = {
      playerPromise.failure(exception)
      this
    }

    /**
      * Sends the given command to the actor under test.
      *
      * @param command the command to send
      * @return this test helper
      */
    def sendCommand(command: AudioPlayerManagementCommand): ManagerActorTestHelper = {
      managerActor ! command
      this
    }

    /**
      * Verifies that the given factory was added to the managed player.
      *
      * @param factory the factory to check
      * @param mode    the verification mode
      * @return this test helper
      */
    def verifyFactoryAdded(factory: PlaybackContextFactory,
                           mode: VerificationMode = DefaultVerificationMode): ManagerActorTestHelper = {
      verify(player, mode).addPlaybackContextFactory(factory)
      this
    }

    /**
      * Verifies that the given factory was removed from the managed player.
      *
      * @param factory the factory to check
      * @param mode    the verification mode
      * @return this test helper
      */
    def verifyFactoryRemoved(factory: PlaybackContextFactory,
                             mode: VerificationMode = DefaultVerificationMode): ManagerActorTestHelper = {
      verify(player, mode).removePlaybackContextFactory(factory)
      this
    }

    /**
      * Checks whether the player controller has been correctly registered at
      * the message bus.
      *
      * @return this test helper
      */
    def checkControllerRegistration(): ManagerActorTestHelper = {
      val regMsg = messageBus.expectMessageType[RegisterService]
      regMsg.service.serviceName should be("lineDJ.audioPlayerController")
      messageBus.currentListeners should have size 1
      this
    }

    /**
      * Checks whether all registration steps are reverted on closing the
      * player.
      *
      * @return this test helper
      */
    def checkControllerDeRegistration(): ManagerActorTestHelper = {
      val unRegMsg = messageBus.expectMessageType[UnregisterService]
      unRegMsg.service.serviceName should be("lineDJ.audioPlayerController")
      messageBus.currentListeners shouldBe empty
      this
    }

    /**
      * Obtains the event actor that has been registered at the player.
      *
      * @return the event actor
      */
    def fetchEventListenerActor(): ActorRef[PlayerEvent] = {
      val capture = ArgumentCaptor.forClass(classOf[ActorRef[PlayerEvent]])
      verify(player, DefaultVerificationMode).addEventListener(capture.capture())
      capture.getValue
    }

    /**
      * Verifies that the event listener was correctly removed at the player.
      *
      * @return this test helper
      */
    def verifyEventListenerRemoved(): ManagerActorTestHelper = {
      val listener = fetchEventListenerActor()
      verify(player, DefaultVerificationMode).removeEventListener(listener)
      this
    }

    /**
      * Prepares the mock for the audio player to expect a ''close()'' call and
      * sets the result for this call.
      *
      * @param result the ''Future'' to be returned by close()
      * @return this test helper
      */
    def prepareClosing(result: Future[Seq[CloseAck]]): ManagerActorTestHelper = {
      implicit val ec: ExecutionContext = testKit.system.executionContext
      when(player.close()).thenReturn(result)
      this
    }

    /**
      * Sends a ''Close'' command to the test actor. Returns the test probe
      * that was passed as client.
      *
      * @return the test probe that should receive the ''CloseAck''
      */
    def closePlayer(): TestProbe[AudioPlayerManagerActor.CloseAck] = {
      val probe = testKit.createTestProbe[AudioPlayerManagerActor.CloseAck]()
      managerActor ! AudioPlayerManagerActor.Close(probe.ref, CloseTimeout)
      probe
    }

    /**
      * Checks whether the actor under test has stopped itself.
      *
      * @return this test helper
      */
    def checkActorStopped(): ManagerActorTestHelper = {
      val probe = testKit.createTestProbe()
      probe.expectTerminated(managerActor)
      this
    }

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
    private def createManagerActor(): ActorRef[AudioPlayerManagementCommand] = {
      val creator: AudioPlayerManagerActor.ControllerCreationFunc = () => playerPromise.future
      testKit.spawn(AudioPlayerManagerActor(messageBus)(creator))
    }
  }
}
