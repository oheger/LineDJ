/*
 * Copyright 2015-2024 The Developers Team.
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

import de.oliver_heger.linedj.platform.ui.TextTimeFunctions
import de.oliver_heger.linedj.player.engine.radio.RadioSource
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.components.WidgetHandler
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler
import net.sf.jguiraffe.resources.Message

import scala.concurrent.duration.FiniteDuration

object RadioStatusLineController:
  /** Resource key for the status text for normal playback. */
  private val ResKeyStatusPlaybackNormal = "txt_status_playback"

  /** Resource key for the status text for replacement playback. */
  private val ResKeyStatusPlaybackReplace = "txt_status_replacement"

  /** Resource key for the initialization error message. */
  private val ResKeyStatusPlayerInitError = "txt_status_error"

/**
  * A helper class to manage the status line of the radio player application.
  *
  * An instance is notified about certain changes in the status of radio
  * playback, such as playback progress, errors, or replacement sources. It is
  * then responsible for updating the status line accordingly.
  *
  * All functions must be invoked in the event thread only.
  *
  * @param applicationContext the application context
  * @param statusText         the handler for the status line
  * @param playbackTime       the handler for the field with the playback time
  * @param errorIndicator     handler to the error indicator field
  */
class RadioStatusLineController(applicationContext: ApplicationContext,
                                statusText: StaticTextHandler,
                                playbackTime: StaticTextHandler,
                                errorIndicator: WidgetHandler):

  import RadioStatusLineController._

  /** Stores information about radio sources and their names. */
  private val radioSources = RadioPlayerClientConfig(applicationContext).sourceConfig.namedSources

  /** The function to generate the playback time text. */
  private val playbackTimeFunc = TextTimeFunctions.formattedTime()

  /** Stores the currently selected source. */
  private var currentSource: RadioSource = _

  /**
    * Stores the source that is currently played. This is not necessarily the
    * selected source, but could also be a replacement.
    */
  private var playedSource: RadioSource = _

  /**
    * Stores a flag whether an error was recorded when playing the selected
    * source. In this state, the error indicator is displayed until the
    * selected source could be played successfully again.
    */
  private var errorState = false

  /**
    * Notifies this object of a change in the selected radio source.
    *
    * @param source the new current radio source
    */
  def updateCurrentSource(source: RadioSource): Unit =
    currentSource = source

  /**
    * Notifies this object about a playback error of the given source. If this
    * is the current radio source, error state is entered.
    *
    * @param source the source affected by the error
    */
  def playbackError(source: RadioSource): Unit =
    if source == currentSource then
      errorState = true
    updateErrorIndicator()

  /**
    * Notifies this object that the given source has been played for a while.
    * This may cause some state updates.
    *
    * @param source the source that is currently played
    * @param time   the playback time
    */
  def playbackTimeProgress(source: RadioSource, time: FiniteDuration): Unit =
    playbackTime setText playbackTimeFunc(time)

    if source != playedSource then
      playedSource = source
      statusText.setText(generateStatusText(source))

    if source == currentSource then
      errorState = false
    updateErrorIndicator()

  /**
    * Notifies this object that the radio player could not be initialized. The
    * controller then displays a corresponding message in the status line.
    */
  def playerInitializationFailed(): Unit =
    statusText.setText(applicationContext.getResourceText(ResKeyStatusPlayerInitError))

  /**
    * Generates the text for the status line when playback of a source starts.
    *
    * @param src the source which is currently played
    * @return the corresponding text for the status line
    */
  private def generateStatusText(src: RadioSource): String =
    if src == currentSource then
      applicationContext.getResourceText(ResKeyStatusPlaybackNormal)
    else
      val srcData = radioSources.find(_._2 == src)
      applicationContext.getResourceText(new Message(null, ResKeyStatusPlaybackReplace,
        srcData.map(_._1) getOrElse src.uri))

  /**
    * Changes the visibility of the error indicator to match the current
    * error state. If the visibility already corresponds to the error state, no
    * action is taken.
    */
  private def updateErrorIndicator(): Unit =
    if errorIndicator.isVisible != errorState then
      errorIndicator.setVisible(errorState)
