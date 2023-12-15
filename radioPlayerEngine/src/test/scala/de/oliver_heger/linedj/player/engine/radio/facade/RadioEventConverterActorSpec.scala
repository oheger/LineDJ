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

package de.oliver_heger.linedj.player.engine.radio.facade

import de.oliver_heger.linedj.{ActorTestKitSupport, AsyncTestHelper}
import de.oliver_heger.linedj.player.engine.*
import de.oliver_heger.linedj.player.engine.actors.EventManagerActor
import de.oliver_heger.linedj.player.engine.radio.*
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

object RadioEventConverterActorSpec:
  /** A test audio source used in player events. */
  private val TestAudioSource = AudioSource("https://audio.example.org/test/stream.mp3", -1, 0, 0)

  /** The corresponding test radio source. */
  private val TestRadioSource = RadioSource("https://radio.example.org/cool-music/", Some("mp3"))

/**
  * Test class for [[RadioEventConverterActor]].
  */
class RadioEventConverterActorSpec extends AnyFlatSpec with Matchers with ActorTestKitSupport
  with AsyncTestHelper:

  import RadioEventConverterActorSpec.*

  "RadioEventConverterActor" should "convert a playback progress event" in:
    val playerEvent = PlaybackProgressEvent(bytesProcessed = 20221221203108L,
      playbackTime = 90 * 60.seconds,
      currentSource = TestAudioSource)
    val radioEvent = RadioPlaybackProgressEvent(source = TestRadioSource,
      bytesProcessed = playerEvent.bytesProcessed,
      playbackTime = playerEvent.playbackTime,
      time = playerEvent.time)
    val helper = new ConverterActorTestHelper

    helper.sendRadioSourceChangedEvent()
      .expectConversion(playerEvent, radioEvent)

  it should "convert a playback context creation failed event" in:
    val playerEvent = PlaybackContextCreationFailedEvent(source = TestAudioSource)
    val radioEvent = RadioPlaybackContextCreationFailedEvent(source = TestRadioSource, time = playerEvent.time)
    val helper = new ConverterActorTestHelper

    helper.sendRadioSourceChangedEvent()
      .expectConversion(playerEvent, radioEvent)

  it should "convert a playback error event" in:
    val playerEvent = PlaybackErrorEvent(source = TestAudioSource)
    val radioEvent = RadioPlaybackErrorEvent(source = TestRadioSource, time = playerEvent.time)
    val helper = new ConverterActorTestHelper

    helper.sendRadioSourceChangedEvent()
      .expectConversion(playerEvent, radioEvent)

  it should "handle the corner case that no current source is available" in:
    val radioSource = RadioSource(TestAudioSource.uri)
    val playerEvent = PlaybackErrorEvent(source = TestAudioSource)
    val radioEvent = RadioPlaybackErrorEvent(source = radioSource, time = playerEvent.time)
    val helper = new ConverterActorTestHelper

    helper.expectConversion(playerEvent, radioEvent)

  it should "stop itself on receiving a Stop command" in:
    val helper = new ConverterActorTestHelper

    helper.checkStop()

  /**
    * A test helper class managing the dependencies of a test instance.
    */
  private class ConverterActorTestHelper:
    /** A test probe for the event manager actor. */
    private val probeEventManager = testKit.createTestProbe[EventManagerActor.EventManagerCommand[RadioEvent]]()

    /** Stores the listener actor registered by the event manager. */
    private var radioListener: ActorRef[RadioEvent] = _

    /** The actor to be tested. */
    private val converterActor = createConverterActor()

    /**
      * Sends an event about a changed radio source to the radio event listener
      * registered by the converter actor.
      *
      * @return this test helper
      */
    def sendRadioSourceChangedEvent(): ConverterActorTestHelper =
      radioListener ! RadioSourceChangedEvent(TestRadioSource)
      this

    /**
      * Executes a test for an event that is expected to be converted.
      *
      * @param playerEvent the original player event
      * @param radioEvent  the expected converted radio event
      */
    def expectConversion(playerEvent: PlayerEvent, radioEvent: RadioEvent): Unit =
      val futPlayerListener = converterActor.ask[RadioEventConverterActor.PlayerListenerReference] { ref =>
        RadioEventConverterActor.GetPlayerListener(ref)
      }
      val playerListener = futureResult(futPlayerListener).listener

      playerListener ! playerEvent
      probeEventManager.expectMessage(EventManagerActor.Publish(radioEvent))

    /**
      * Tests whether the converter actor can stop itself.
      */
    def checkStop(): Unit =
      converterActor ! RadioEventConverterActor.Stop

      probeEventManager.expectTerminated(converterActor)

    /**
      * Creates the converter actor to be tested. This function also obtains
      * the event listener for radio events that should be registered at the
      * event manager actor.
      *
      * @return the converter actor to be tested
      */
    private def createConverterActor(): ActorRef[RadioEventConverterActor.RadioEventConverterCommand] =
      val converter = testKit.spawn(RadioEventConverterActor(probeEventManager.ref))
      val reg = probeEventManager.expectMessageType[EventManagerActor.RegisterListener[RadioEvent]]
      radioListener = reg.listener
      converter
