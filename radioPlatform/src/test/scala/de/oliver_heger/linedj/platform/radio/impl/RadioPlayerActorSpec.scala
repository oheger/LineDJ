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

package de.oliver_heger.linedj.platform.radio.impl

import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.radio.{RadioPlayerCommands, RadioPlayerState}
import de.oliver_heger.linedj.player.server.model.RadioModel
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{timeout as mockTimeout, verify, when}
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration.*
import scala.concurrent.{Future, Promise}

object RadioPlayerActorSpec:
  /** The URI of the radio player server used by the tests. */
  private val ServerUri = "https://radio.example.com"

  /** The timeout for waiting on asynchronous conditions. */
  private val WaitTimeout = Timeout(Span(3, Seconds))

  /** The interval for polling asynchronous conditions. */
  private val WaitInterval = Interval(Span(10, Millis))

  /** The timeout in milliseconds for Mockito verifications. */
  private val VerifyTimeout = 3000L

  /** The radio sources returned by the player service in tests. */
  private val TestSources = RadioModel.RadioSources(
    List(
      RadioModel.RadioSource("source1", "Source 1", 1),
      RadioModel.RadioSource("source2", "Source 2", 2)
    )
  )

  /** The ID of the first test source. */
  private val SourceID1 = TestSources.sources.head.id

  /** The ID of the second test source. */
  private val SourceID2 = TestSources.sources(1).id

  /** A title used by tests. */
  private val TestTitle = "Artist - Title"

  /**
    * Creates an expected player state.
    *
    * @param radioSources       the expected radio sources
    * @param optCurrentSourceID the expected current source ID
    * @param optReplacementID   the expected replacement source ID
    * @param playbackEnabled    the expected playback flag
    * @param optTitle           the expected title
    * @return the expected state
    */
  private def expectedState(radioSources: List[RadioModel.RadioSource] = List.empty,
                            optCurrentSourceID: Option[String] = None,
                            optReplacementID: Option[String] = None,
                            playbackEnabled: Boolean = false,
                            optTitle: Option[String] = None): RadioPlayerState =
    RadioPlayerState(radioSources, optCurrentSourceID, optReplacementID, playbackEnabled, optTitle)
end RadioPlayerActorSpec

/**
  * Test class for [[RadioPlayerActor]].
  */
class RadioPlayerActorSpec extends ScalaTestWithActorTestKit, AnyFlatSpecLike, Matchers, MockitoSugar, Eventually:

  import RadioPlayerActorSpec.*

  "A RadioPlayerActor" should "start playback on a corresponding command" in :
    val helper = new ActorTestHelper

    helper.sendCommand(RadioPlayerCommands.StartPlayback)
      .verifyPlayerService(_.startPlayback())

  it should "stop playback on a corresponding command" in :
    val helper = new ActorTestHelper

    helper.sendCommand(RadioPlayerCommands.StopPlayback)
      .verifyPlayerService(_.stopPlayback())

  it should "select a radio source on a corresponding command" in :
    val helper = new ActorTestHelper
    val sourceID = "test-source-id"

    helper.sendCommand(RadioPlayerCommands.SelectRadioSource(sourceID))
      .verifyPlayerService(_.switchToSource(sourceID))

  it should "publish an updated state when the radio sources have been loaded" in :
    val helper = new ActorTestHelper

    helper.setRadioSources(TestSources)

    val state = helper.messageBus.expectMessageType[RadioPlayerState]
    state.radioSources should contain theSameElementsInOrderAs TestSources.sources

  it should "keep the radio sources in states published for message updates" in :
    val helper = new ActorTestHelper

    helper.setRadioSources(TestSources)
    helper.expectState() should be(expectedState(radioSources = TestSources.sources))

    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypeSourceSelected, SourceID1))

    val expected = expectedState(radioSources = TestSources.sources, optCurrentSourceID = Some(SourceID1))
    helper.expectState() should be(expected)

  it should "not publish a state update if loading the radio sources fails" in :
    val helper = new ActorTestHelper

    helper.failRadioSources(new RuntimeException("Test exception"))

    helper.expectNoStateUpdate()

  it should "update the current source on a SourceSelected message" in :
    val helper = new ActorTestHelper

    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypeSourceSelected, SourceID1))

    helper.expectState() should be(expectedState(optCurrentSourceID = Some(SourceID1)))

  it should "update the state on a SourceChanged message" in :
    val helper = new ActorTestHelper

    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypeSourceChanged, SourceID1))

    helper.expectState() should be(expectedState(optCurrentSourceID = Some(SourceID1), playbackEnabled = true))

  it should "handle a replacement source start message" in :
    val helper = new ActorTestHelper

    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypeSourceChanged, SourceID1))
    helper.expectState()
    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypeReplacementStart, SourceID2))

    val expected = expectedState(
      optCurrentSourceID = Some(SourceID1),
      optReplacementID = Some(SourceID2),
      playbackEnabled = true
    )
    helper.expectState() should be(expected)

  it should "not overwrite the current source when the replacement starts playing" in :
    val helper = new ActorTestHelper

    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypeSourceChanged, SourceID1))
    helper.expectState()
    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypeReplacementStart, SourceID2))
    helper.expectState()
    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypeSourceChanged, SourceID2))
    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypeTitleInfo, TestTitle))

    val expected = expectedState(
      optCurrentSourceID = Some(SourceID1),
      optReplacementID = Some(SourceID2),
      playbackEnabled = true,
      optTitle = Some(TestTitle)
    )
    helper.expectState() should be(expected)

  it should "handle a replacement source end message" in :
    val helper = new ActorTestHelper

    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypeSourceChanged, SourceID1))
    helper.expectState()
    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypeReplacementStart, SourceID2))
    helper.expectState()
    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypeReplacementEnd, SourceID1))

    helper.expectState() should be(expectedState(optCurrentSourceID = Some(SourceID1), playbackEnabled = true))

  it should "handle a playback stopped message" in :
    val helper = new ActorTestHelper

    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypeSourceChanged, SourceID1))
    helper.expectState()
    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypePlaybackStopped, SourceID1))

    helper.expectState() should be(expectedState(optCurrentSourceID = Some(SourceID1)))

  it should "keep the source information when playback stops during replacement" in :
    val helper = new ActorTestHelper

    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypeSourceChanged, SourceID1))
    helper.expectState()
    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypeReplacementStart, SourceID2))
    helper.expectState()
    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypePlaybackStopped, SourceID2))

    val expected = expectedState(optCurrentSourceID = Some(SourceID1), optReplacementID = Some(SourceID2))
    helper.expectState() should be(expected)

  it should "handle title info messages" in :
    val helper = new ActorTestHelper

    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypeTitleInfo, TestTitle))
    helper.expectState() should be(expectedState(optTitle = Some(TestTitle)))

    helper.sendRadioMessage(RadioModel.RadioMessage(RadioModel.MessageTypeTitleInfo, ""))
    helper.expectState() should be(expectedState())

  it should "ignore unknown radio message types" in :
    val helper = new ActorTestHelper

    helper.sendRadioMessage(RadioModel.RadioMessage("UnknownMessageType", SourceID1))

    helper.expectNoStateUpdate()

  it should "release its resources when stopped" in :
    val helper = new ActorTestHelper

    helper.stopActor()
      .verifyMessageActorStopped()

  /**
    * A test helper class managing the actor under test and its dependencies.
    */
  private class ActorTestHelper:
    /** The message bus passed to the actor under test. */
    val messageBus: MessageBusTestImpl = new MessageBusTestImpl()

    /** A promise for the radio sources returned by the player service. */
    private val radioSourcesPromise = Promise[RadioModel.RadioSources]()

    /** A mock for the player service. */
    private val playerService = createPlayerService()

    /** A probe monitoring the message actor. */
    private val messageActorProbe = testKit.createTestProbe[RadioMessageActor.Command]()

    /** A mock for the factory of the message actor. */
    private val messageActorFactory = createMessageActorFactory()

    /** The actor reference passed to the message actor factory. */
    private lazy val messageActorRef = fetchMessageActorReference()

    /** The actor under test. */
    private val actor: ActorRef[RadioPlayerActor.Command] = createActor()

    /**
      * Publishes the given command on the message bus.
      *
      * @param command the command to send
      * @return this test helper
      */
    def sendCommand(command: RadioPlayerCommands): ActorTestHelper =
      messageBus.publishDirectly(command)
      this

    /**
      * Completes the promise for the radio sources with the given result.
      *
      * @param sources the radio sources to report
      * @return this test helper
      */
    def setRadioSources(sources: RadioModel.RadioSources): ActorTestHelper =
      radioSourcesPromise.success(sources)
      this

    /**
      * Fails the promise for the radio sources with the given exception.
      *
      * @param exception the exception to report
      * @return this test helper
      */
    def failRadioSources(exception: Throwable): ActorTestHelper =
      radioSourcesPromise.failure(exception)
      this

    /**
      * Sends a radio message to the message actor of the actor under test.
      *
      * @param message the message to send
      * @return this test helper
      */
    def sendRadioMessage(message: RadioModel.RadioMessage): ActorTestHelper =
      messageActorRef ! message
      this

    /**
      * Returns the next state published on the message bus.
      *
      * @return the state published by the actor under test
      */
    def expectState(): RadioPlayerState = messageBus.expectMessageType[RadioPlayerState]

    /**
      * Verifies that the actor under test has not published a state update.
      *
      * @return this test helper
      */
    def expectNoStateUpdate(): ActorTestHelper =
      messageBus.expectNoMessage(250.millis)
      this

    /**
      * Verifies an interaction with the player service. The function is called
      * with a verification proxy for the mock, allowing the test to specify
      * the expected invocation.
      *
      * @param f the verification function
      * @return this test helper
      */
    def verifyPlayerService(f: PlayerService => Unit): ActorTestHelper =
      f(verify(playerService, mockTimeout(VerifyTimeout)))
      this

    /**
      * Stops the actor under test and verifies that it unregistered its
      * listener from the message bus.
      *
      * @return this test helper
      */
    def stopActor(): ActorTestHelper =
      actor ! RadioPlayerActor.Command.Stop
      eventually(WaitTimeout, WaitInterval):
        messageBus.currentListeners shouldBe empty
      this

    /**
      * Verifies that the message actor has been sent a Stop command.
      *
      * @return this test helper
      */
    def verifyMessageActorStopped(): ActorTestHelper =
      messageActorProbe.expectMessage(RadioMessageActor.Stop)
      this

    /**
      * Creates a mock for the player service that accepts all requests.
      *
      * @return the mock player service
      */
    private def createPlayerService(): PlayerService =
      val service = mock[PlayerService]
      when(service.startPlayback()).thenReturn(Future.successful(()))
      when(service.stopPlayback()).thenReturn(Future.successful(()))
      when(service.switchToSource(anyString)).thenReturn(Future.successful(()))
      when(service.radioSources).thenReturn(radioSourcesPromise.future)
      service

    /**
      * Creates a mock for the factory of the message actor. The returned
      * behavior is monitored by a test probe, so messages sent to the actor can
      * be verified. No real connection to the server is established.
      *
      * @return the mock factory
      */
    private def createMessageActorFactory(): RadioMessageActor.Factory =
      val factory = mock[RadioMessageActor.Factory]
      when(factory.apply(anyString, any[ActorRef[RadioModel.RadioMessage]],
        any[RadioMessageActor.ConnectionFactory])).thenReturn(
        Behaviors.monitor(messageActorProbe.ref, Behaviors.ignore[RadioMessageActor.Command]))
      factory

    /**
      * Obtains the actor reference that was passed to the factory for the 
      * radio message actor to receive messages from the player server.
      *
      * @return the actor reference to receive radio messages
      */
    private def fetchMessageActorReference(): ActorRef[RadioModel.RadioMessage] =
      val captor = ArgumentCaptor.forClass(classOf[ActorRef[RadioModel.RadioMessage]])
      verify(messageActorFactory, mockTimeout(VerifyTimeout)).apply(
        anyString, captor.capture(), any[RadioMessageActor.ConnectionFactory])
      captor.getValue

    /**
      * Creates the actor under test. Waits until the message bus listener has
      * been registered, so that commands are not sent too early.
      *
      * @return the actor under test
      */
    private def createActor(): ActorRef[RadioPlayerActor.Command] =
      val ref = testKit.spawn(
        RadioPlayerActor.newInstance(ServerUri, playerService, messageBus, messageActorFactory))
      eventually(WaitTimeout, WaitInterval):
        messageBus.currentListeners should not be empty
      ref
