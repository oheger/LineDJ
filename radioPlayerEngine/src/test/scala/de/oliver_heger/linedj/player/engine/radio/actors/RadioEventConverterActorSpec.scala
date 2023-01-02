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

package de.oliver_heger.linedj.player.engine.radio.actors

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.AskPattern._
import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.player.engine.radio._
import de.oliver_heger.linedj.player.engine._
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

object RadioEventConverterActorSpec {
  /** A test audio source used in player events. */
  private val TestAudioSource = AudioSource("https://audio.example.org/test/stream.mp3", -1, 0, 0)

  /** The corresponding test radio source. */
  private val TestRadioSource = RadioSource(TestAudioSource.uri)
}

/**
  * Test class for [[RadioEventConverterActor]].
  */
class RadioEventConverterActorSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with AsyncTestHelper {

  import RadioEventConverterActorSpec._

  "RadioEventConverterActor" should "convert a playback progress event" in {
    val playerEvent = PlaybackProgressEvent(bytesProcessed = 20221221203108L,
      playbackTime = 90 * 60.seconds,
      currentSource = TestAudioSource)
    val radioEvent = RadioPlaybackProgressEvent(source = TestRadioSource,
      bytesProcessed = playerEvent.bytesProcessed,
      playbackTime = playerEvent.playbackTime,
      time = playerEvent.time)
    val helper = new ConverterActorTestHelper

    helper.expectConversion(playerEvent, radioEvent)
  }

  it should "convert a playback context creation failed event" in {
    val playerEvent = PlaybackContextCreationFailedEvent(source = TestAudioSource)
    val radioEvent = RadioPlaybackContextCreationFailedEvent(source = TestRadioSource, time = playerEvent.time)
    val helper = new ConverterActorTestHelper

    helper.expectConversion(playerEvent, radioEvent)
  }

  it should "convert a playback error event" in {
    val playerEvent = PlaybackErrorEvent(source = TestAudioSource)
    val radioEvent = RadioPlaybackErrorEvent(source = TestRadioSource, time = playerEvent.time)
    val helper = new ConverterActorTestHelper

    helper.expectConversion(playerEvent, radioEvent)
  }

  it should "stop itself on receiving a Stop command" in {
    val helper = new ConverterActorTestHelper

    helper.checkStop()
  }

  /**
    * A test helper class managing the dependencies of a test instance.
    */
  private class ConverterActorTestHelper {
    /** A test probe acting as listener for radio events. */
    private val probeRadioListener = testKit.createTestProbe[RadioEvent]()

    /** The actor to be tested. */
    private val converterActor = testKit.spawn(RadioEventConverterActor(probeRadioListener.ref))

    /**
      * Executes a test for an event that is expected to be converted.
      *
      * @param playerEvent the original player event
      * @param radioEvent  the expected converted radio event
      */
    def expectConversion(playerEvent: PlayerEvent, radioEvent: RadioEvent): Unit = {
      val futPlayerListener = converterActor.ask[RadioEventConverterActor.PlayerListenerReference] { ref =>
        RadioEventConverterActor.GetPlayerListener(ref)
      }
      val playerListener = futureResult(futPlayerListener).listener

      playerListener ! playerEvent
      probeRadioListener.expectMessage(radioEvent)
    }

    /**
      * Tests whether the converter actor can stop itself.
      */
    def checkStop(): Unit = {
      converterActor ! RadioEventConverterActor.Stop

      probeRadioListener.expectTerminated(converterActor)
    }
  }
}
