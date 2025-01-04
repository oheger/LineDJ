/*
 * Copyright 2015-2025 The Developers Team.
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

import de.oliver_heger.linedj.player.engine.radio.RadioSource
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.components.WidgetHandler
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler
import net.sf.jguiraffe.resources.Message
import org.apache.commons.configuration.XMLConfiguration
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

object RadioStatusLineControllerSpec:
  /** A text resource for the status line. */
  private val StatusText = "Text for the status line"

  /** A test radio source from the test configuration file. */
  private val TestRadioSource = RadioSource("http://metafiles.gl-systemhaus.de/hr/hr1_2.m3u", Some("mp3"))

  /** The name of the test source. */
  private val TestRadioSourceName = "HR 1"

/**
  * Test class for [[RadioStatusLineController]].
  */
class RadioStatusLineControllerSpec extends AnyFlatSpec with Matchers with MockitoSugar:

  import RadioStatusLineControllerSpec._

  "RadioStatusLineController" should "update the status text if the current source is played" in:
    val helper = new StatusLineControllerTestHelper
    helper.expectResource("txt_status_playback")

    helper.controller.updateCurrentSource(TestRadioSource)
    helper.controller.playbackTimeProgress(TestRadioSource, 10.seconds)

    helper.verifyStatusUpdated()

  it should "update the status text if a replacement source is played" in:
    val msg = new Message(null, "txt_status_replacement", TestRadioSourceName)
    val helper = new StatusLineControllerTestHelper
    helper.expectMessageResource(msg)

    helper.controller.updateCurrentSource(RadioSource("anotherSource"))
    helper.controller.playbackTimeProgress(TestRadioSource, 11.seconds)

    helper.verifyStatusUpdated()

  it should "update the status text if an unknown replacement source is played" in:
    val replacementSource = RadioSource("https://radio.example.org/unknown_source")
    val msg = new Message(null, "txt_status_replacement", replacementSource.uri)
    val helper = new StatusLineControllerTestHelper
    helper.expectMessageResource(msg)

    helper.controller.updateCurrentSource(TestRadioSource)
    helper.controller.playbackTimeProgress(replacementSource, 12.seconds)

    helper.verifyStatusUpdated()

  it should "not update the status text if there is no change in the source" in:
    val helper = new StatusLineControllerTestHelper
    helper.expectResource("txt_status_playback")
    helper.controller.updateCurrentSource(TestRadioSource)
    helper.controller.playbackTimeProgress(TestRadioSource, 10.seconds)

    helper.controller.playbackTimeProgress(TestRadioSource, 20.seconds)

    helper.verifyStatusUpdated()

  it should "update the playback time" in:
    val helper = new StatusLineControllerTestHelper

    helper.controller.playbackTimeProgress(TestRadioSource, 65.seconds)

    verify(helper.playbackTimeHandler).setText("1:05")

  it should "show the error indicator if the current source is in error state" in:
    val helper = new StatusLineControllerTestHelper

    helper.controller.updateCurrentSource(TestRadioSource)
    helper.controller.playbackError(TestRadioSource)

    verify(helper.errorIndicator).setVisible(true)

  it should "not show the error indicator if an error for another source is reported" in:
    val helper = new StatusLineControllerTestHelper

    helper.controller.playbackError(TestRadioSource)

    verify(helper.errorIndicator, never()).setVisible(true)

  it should "hide the error indicator if the current source is played again" in:
    val helper = new StatusLineControllerTestHelper
    when(helper.errorIndicator.isVisible).thenReturn(true)
    helper.controller.updateCurrentSource(TestRadioSource)
    helper.controller.playbackError(TestRadioSource)

    helper.controller.playbackTimeProgress(TestRadioSource, 1.second)

    verify(helper.errorIndicator).setVisible(false)

  it should "not hide the error indicator if another source is played" in:
    val otherSource = RadioSource("anotherSource")
    val helper = new StatusLineControllerTestHelper
    when(helper.errorIndicator.isVisible).thenReturn(true)
    helper.controller.updateCurrentSource(TestRadioSource)
    helper.controller.playbackError(TestRadioSource)

    helper.controller.playbackTimeProgress(otherSource, 1.second)

    verify(helper.errorIndicator, never()).setVisible(false)

  it should "not hide the error indicator if it is not visible" in:
    val helper = new StatusLineControllerTestHelper
    when(helper.errorIndicator.isVisible).thenReturn(false)
    helper.controller.updateCurrentSource(TestRadioSource)

    helper.controller.playbackTimeProgress(TestRadioSource, 1.second)

    verify(helper.errorIndicator, never()).setVisible(false)

  it should "hide the error indicator if playback starts with a replacement source" in:
    val replacementSource = RadioSource("replacement")
    val helper = new StatusLineControllerTestHelper
    when(helper.errorIndicator.isVisible).thenReturn(true)
    helper.controller.updateCurrentSource(TestRadioSource)

    helper.controller.playbackTimeProgress(replacementSource, 2.seconds)

    verify(helper.errorIndicator).setVisible(false)

  it should "update the status text with an initialization error message" in:
    val helper = new StatusLineControllerTestHelper
    helper.expectResource("txt_status_error")

    helper.controller.playerInitializationFailed()

    helper.verifyStatusUpdated()

  /**
    * A test helper class managing a test instance and its dependencies.
    */
  private class StatusLineControllerTestHelper:
    /** Mock for the application context. */
    private val applicationContext: ApplicationContext = createApplicationContext()

    /** Mock for the status line handler. */
    private val statusHandler: StaticTextHandler = mock[StaticTextHandler]

    /** Mock for the handler for the playback time. */
    val playbackTimeHandler: StaticTextHandler = mock[StaticTextHandler]

    /** Mock for the error indicator control. */
    val errorIndicator: WidgetHandler = mock[WidgetHandler]

    /** The controller to be tested. */
    val controller = new RadioStatusLineController(applicationContext, statusHandler, playbackTimeHandler,
      errorIndicator)

    /**
      * Prepares the mock application context for a resource request based on a
      * resource key.
      *
      * @param key  the resource key
      * @param text the text to be returned
      * @return this test helper
      */
    def expectResource(key: AnyRef, text: String = StatusText): StatusLineControllerTestHelper =
      when(applicationContext.getResourceText(key)).thenReturn(text)
      this

    /**
      * Prepares the mock application context for a resource request based on a
      * ''Message'' object.
      *
      * @param msg  the expected message object
      * @param text the text to be returned
      * @return this test helper
      */
    def expectMessageResource(msg: Message, text: String = StatusText): StatusLineControllerTestHelper =
      when(applicationContext.getResourceText(msg)).thenReturn(text)
      this

    /**
      * Verifies that the status text has been updated as expected.
      */
    def verifyStatusUpdated(): Unit =
      verify(statusHandler).setText(StatusText)

    /**
      * Creates a mock for the application context and configures it with a
      * test radio player configuration.
      *
      * @return the initialized mock application context
      */
    private def createApplicationContext(): ApplicationContext =
      val appCtx = mock[ApplicationContext]
      val config = new XMLConfiguration("test-radio-configuration.xml")
      when(appCtx.getConfiguration).thenReturn(config)
      appCtx
