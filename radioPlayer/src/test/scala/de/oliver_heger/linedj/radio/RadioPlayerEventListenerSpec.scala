/*
 * Copyright 2015-2023 The Developers Team.
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
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.player.engine.radio._
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

/**
  * Test class for ''RadioPlayerEventListener''.
  */
class RadioPlayerEventListenerSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  "A RadioPlayerEventListener" should "delegate radio source events" in {
    val source = RadioSource("testSource")
    val helper = new RadioPlayerEventListenerTestHelper

    helper sendEvent RadioSourceChangedEvent(source)
    verify(helper.controller).radioSourcePlaybackStarted(source)
  }

  it should "delegate playback progress events" in {
    val source = RadioSource("testSource")
    val PlaybackTime = 128.seconds
    val helper = new RadioPlayerEventListenerTestHelper

    helper sendEvent RadioPlaybackProgressEvent(source, 100, PlaybackTime, null)
    verify(helper.controller).playbackTimeProgress(PlaybackTime)
  }

  it should "delegate radio source error events" in {
    val errorEvent = RadioSourceErrorEvent(RadioSource("errorSource"))
    val helper = new RadioPlayerEventListenerTestHelper

    helper.sendEvent(errorEvent).verifyErrorEvent(errorEvent)
  }

  it should "delegate playback context creation failure events" in {
    val pcEvent = RadioPlaybackContextCreationFailedEvent(RadioSource("failed"))
    val helper = new RadioPlayerEventListenerTestHelper

    helper.sendEvent(pcEvent)
    verify(helper.controller).playbackContextCreationFailed()
  }

  it should "delegate playback error events" in {
    val errEvent = RadioPlaybackErrorEvent(RadioSource("corrupt"))
    val helper = new RadioPlayerEventListenerTestHelper

    helper sendEvent errEvent
    verify(helper.controller).playbackContextCreationFailed()
  }

  it should "delegate replacement source start events" in {
    val replacementSource = RadioSource("ReplacementSource")
    val startEvent = RadioSourceReplacementStartEvent(RadioSource("current"), replacementSource)
    val helper = new RadioPlayerEventListenerTestHelper

    helper sendEvent startEvent
    verify(helper.controller).replacementSourceStarts(replacementSource)
  }

  it should "delegate replacement source end events" in {
    val endEvent = RadioSourceReplacementEndEvent(RadioSource("theCurrentSource"))
    val helper = new RadioPlayerEventListenerTestHelper

    helper sendEvent endEvent
    verify(helper.controller).replacementSourceEnds()
  }

  it should "delegate metadata events" in {
    val metadataEvent = RadioMetadataEvent(MetadataNotSupported)
    val helper = new RadioPlayerEventListenerTestHelper

    helper sendEvent metadataEvent
    verify(helper.controller).metadataChanged(metadataEvent)
  }

  it should "ignore other events" in {
    val event = AudioSourceStartedEvent(AudioSource.infinite("Test"))
    val helper = new RadioPlayerEventListenerTestHelper

    helper.listener.receive.isDefinedAt(event) shouldBe false
  }

  private class RadioPlayerEventListenerTestHelper {
    /** Mock for the radio player. */
    val player: RadioPlayer = mock[RadioPlayer]

    /** The mock for the radio controller. */
    val controller: RadioController = createController()

    /** The listener to be tested. */
    val listener = new RadioPlayerEventListener(controller)

    /**
      * Sends a player event to the test listener.
      *
      * @param event the event
      */
    def sendEvent(event: Any): RadioPlayerEventListenerTestHelper = {
      listener receive event
      this
    }

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

    /**
      * Creates a mock for the [[RadioController]] that is prepared to return
      * its associated [[RadioPlayer]].
      *
      * @return the prepared mock controller
      */
    private def createController(): RadioController = {
      val ctrl = mock[RadioController]
      when(ctrl.radioPlayer).thenReturn(player)
      ctrl
    }
  }
}
