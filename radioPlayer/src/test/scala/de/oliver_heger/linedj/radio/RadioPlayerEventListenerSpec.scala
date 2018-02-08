/*
 * Copyright 2015-2018 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.radio

import de.oliver_heger.linedj.player.engine._
import de.oliver_heger.linedj.player.engine.facade.RadioPlayer
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''RadioPlayerEventListener''.
  */
class RadioPlayerEventListenerSpec extends FlatSpec with Matchers with MockitoSugar {
  "A RadioPlayerEventListener" should "delegate radio source events" in {
    val source = RadioSource("testSource")
    val helper = new RadioPlayerEventListenerTestHelper

    helper sendEvent RadioSourceChangedEvent(source)
    verify(helper.controller).radioSourcePlaybackStarted(source)
  }

  it should "delegate playback progress events" in {
    val PlaybackTime = 20160709223424L
    val helper = new RadioPlayerEventListenerTestHelper

    helper sendEvent PlaybackProgressEvent(100, PlaybackTime, null)
    verify(helper.controller).playbackTimeProgress(PlaybackTime)
  }

  it should "delegate radio source error events" in {
    val errorEvent = RadioSourceErrorEvent(RadioSource("errorSource"))
    val helper = new RadioPlayerEventListenerTestHelper

    helper.sendEvent(errorEvent).verifyErrorEvent(errorEvent)
  }

  it should "delegate playback context creation failure events" in {
    val pcEvent = PlaybackContextCreationFailedEvent(AudioSource.infinite("failed"))
    val helper = new RadioPlayerEventListenerTestHelper

    helper.sendEvent(pcEvent)
    verify(helper.controller).playbackContextCreationFailed()
  }

  it should "delegate playback error events" in {
    val errEvent = PlaybackErrorEvent(AudioSource.infinite("corrupt"))
    val helper = new RadioPlayerEventListenerTestHelper

    helper sendEvent errEvent
    verify(helper.controller).playbackContextCreationFailed()
  }

  it should "ignore other events" in {
    val helper = new RadioPlayerEventListenerTestHelper

    helper sendEvent AudioSourceStartedEvent(AudioSource.infinite("Test"))
    verifyZeroInteractions(helper.controller)
  }

  it should "ignore an event from another player" in {
    val otherPlayer = mock[RadioPlayer]
    val helper = new RadioPlayerEventListenerTestHelper
    val event = helper.createRadioPlayerEvent(RadioSourceChangedEvent(RadioSource("?")), Some
    (otherPlayer))

    helper.listener.receive.isDefinedAt(event) shouldBe false
  }

  private class RadioPlayerEventListenerTestHelper {
    /** Mock for the radio player. */
    val player = mock[RadioPlayer]

    /** The mock for the radio controller. */
    val controller = mock[RadioController]

    /** The listener to be tested. */
    val listener = new RadioPlayerEventListener(controller, player)

    /**
      * Sends a player event to the test listener. The event is correctly
      * wrapped, and an optional source can be specified.
      *
      * @param event     the event
      * @param optSource the optional player as source
      */
    def sendEvent(event: PlayerEvent, optSource: Option[RadioPlayer] = None):
    RadioPlayerEventListenerTestHelper = {
      listener receive createRadioPlayerEvent(event, optSource)
      this
    }

    /**
      * Creates the ''RadioPlayerEvent'' to be sent to the listener.
      *
      * @param event     the wrapped player event
      * @param optSource the optional player as source
      * @return the radio player event
      */
    def createRadioPlayerEvent(event: PlayerEvent, optSource: Option[RadioPlayer]):
    RadioPlayerEvent =
      RadioPlayerEvent(event, optSource getOrElse player)

    /**
      * Verifies whether the controller received the specified error event
      * (ignoring the timestamp which might be different).
      *
      * @param event the expected event
      * @return this test helper
      */
    def verifyErrorEvent(event: RadioSourceErrorEvent): RadioPlayerEventListenerTestHelper =
    verifyErrorSource(event.source)

    /**
      * Verifies that the controller was notified about a playback error for the
      * specified radio source.
      *
      * @param source the radio source
      * @return this test helper
      */
    def verifyErrorSource(source: RadioSource): RadioPlayerEventListenerTestHelper = {
      val captor = ArgumentCaptor.forClass(classOf[RadioSourceErrorEvent])
      verify(controller).playbackError(captor.capture())
      captor.getValue.source should be(source)
      this
    }
  }
}
