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
import de.oliver_heger.linedj.player.engine.radio._
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

/**
  * Test class for ''RadioPlayerEventListener''.
  */
class RadioPlayerEventListenerSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  "A RadioPlayerEventListener" should "delegate playback progress events" in:
    val source = RadioSource("testSource")
    val PlaybackTime = 128.seconds
    val helper = new RadioPlayerEventListenerTestHelper

    helper sendEvent RadioPlaybackProgressEvent(source, 100, PlaybackTime, null)

    verify(helper.controller).playbackTimeProgress(source, PlaybackTime)
    verify(helper.statusController).playbackTimeProgress(source, PlaybackTime)

  it should "delegate radio source error events" in:
    val errorEvent = RadioSourceErrorEvent(RadioSource("errorSource"))
    val helper = new RadioPlayerEventListenerTestHelper

    helper.sendEvent(errorEvent)

    verify(helper.statusController).playbackError(errorEvent.source)

  it should "delegate playback context creation failure events" in:
    val pcEvent = RadioPlaybackContextCreationFailedEvent(RadioSource("failed"))
    val helper = new RadioPlayerEventListenerTestHelper

    helper.sendEvent(pcEvent)

    verify(helper.statusController).playbackError(pcEvent.source)

  it should "delegate playback error events" in:
    val errEvent = RadioPlaybackErrorEvent(RadioSource("corrupt"))
    val helper = new RadioPlayerEventListenerTestHelper

    helper.sendEvent(errEvent)

    verify(helper.statusController).playbackError(errEvent.source)

  it should "delegate metadata events" in:
    val metadataEvent = RadioMetadataEvent(RadioSource("metadataSource"), MetadataNotSupported)
    val helper = new RadioPlayerEventListenerTestHelper

    helper sendEvent metadataEvent
    verify(helper.controller).metadataChanged(metadataEvent)

  it should "ignore other events" in:
    val event = AudioSourceStartedEvent(AudioSource.infinite("Test"))
    val helper = new RadioPlayerEventListenerTestHelper

    helper.listener.receive.isDefinedAt(event) shouldBe false

  private class RadioPlayerEventListenerTestHelper:
    /** The mock for the radio controller. */
    val controller: RadioController = mock[RadioController]

    /** Mock for the status line controller. */
    val statusController: RadioStatusLineController = mock[RadioStatusLineController]

    /** The listener to be tested. */
    val listener = new RadioPlayerEventListener(controller, statusController)

    /**
      * Sends a player event to the test listener.
      *
      * @param event the event
      */
    def sendEvent(event: Any): RadioPlayerEventListenerTestHelper =
      listener receive event
      this
