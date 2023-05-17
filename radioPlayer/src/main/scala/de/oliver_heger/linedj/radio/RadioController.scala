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

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.comm.MessageBusListener
import de.oliver_heger.linedj.platform.ui.TextTimeFunctions
import de.oliver_heger.linedj.player.engine.radio._
import de.oliver_heger.linedj.player.engine.radio.config.RadioSourceConfig
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, StaticTextHandler}
import net.sf.jguiraffe.gui.builder.event.{FormChangeEvent, FormChangeListener}
import org.apache.commons.configuration.Configuration
import org.apache.logging.log4j.LogManager

import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object RadioController {
  /**
    * Constant for an initial delay before starting playback (in milliseconds).
    *
    * It might be necessary to wait for a while until the audio player engine
    * is set up, and all playback context factories have been registered. The
    * time to wait can be specified in the configuration. If this is not done,
    * this default value is used.
    */
  final val DefaultInitialDelay = 5000

  /** Common prefix for all configuration keys. */
  private val ConfigKeyPrefix = "radio."

  /** Configuration key for the current source. */
  private val KeyCurrentSource = ConfigKeyPrefix + "current"

  /** The name of the start playback action. */
  private val ActionStartPlayback = "startPlaybackAction"

  /** The name of the stop playback action. */
  private val ActionStopPlayback = "stopPlaybackAction"

  /** Text to be displayed if the radio station does not support metadata. */
  private val UnsupportedMetadataText = ""

  /**
    * A zero duration representing the playback time when switching to another
    * radio source.
    */
  private val InitialPlaybackDuration = FiniteDuration(0, TimeUnit.SECONDS)

  /**
    * A message to be published on the message bus when the initialization of
    * the radio player is complete. That way the [[RadioController]] obtains
    * the initialized player instance. Depending on the result of the
    * initialization, the controller can then update itself.
    *
    * @param triedRadioPlayer a ''Try'' with the [[RadioPlayer]]
    */
  case class RadioPlayerInitialized(triedRadioPlayer: Try[RadioPlayer])
}

/**
  * A controller class responsible for the main window of the radio player
  * application.
  *
  * This controller reads the radio sources available from the passed in
  * configuration and fills a combo box accordingly. Selection changes on the
  * combo box cause the corresponding radio source to be played. It also
  * reacts on actions for starting and stopping playback.
  *
  * @param userConfig           the current user configuration
  * @param applicationContext   the application context
  * @param actionStore          the object for accessing actions
  * @param comboSources         the combo box with the radio sources
  * @param metadataText         handler for the field to display metadata
  * @param statusLineController the controller for the status line
  * @param playerConfig         the configuration for the radio player
  */
class RadioController(val userConfig: Configuration,
                      applicationContext: ApplicationContext,
                      actionStore: ActionStore,
                      comboSources: ListComponentHandler,
                      metadataText: StaticTextHandler,
                      statusLineController: RadioStatusLineController,
                      val playerConfig: RadioPlayerClientConfig)
  extends FormChangeListener with MessageBusListener {

  def this(config: Configuration,
           applicationContext: ApplicationContext,
           actionStore: ActionStore,
           comboSources: ListComponentHandler,
           metadataText: StaticTextHandler,
           statusLineController: RadioStatusLineController) =
    this(config, applicationContext, actionStore, comboSources, metadataText, statusLineController,
      RadioPlayerClientConfig(applicationContext))

  import RadioController._

  /** The logger. */
  private val log = LogManager.getLogger(getClass)

  /** The player managed by this controller. */
  private var player: RadioPlayer = _

  /** Stores the currently available radio sources. */
  private var radioSources = Seq.empty[(String, RadioSource)]

  /** Stores the last received metadata text. */
  private var lastMetadataText = UnsupportedMetadataText

  /**
    * Stores the last playback duration from a playback progress event. This is
    * needed to correctly rotate metadata: here a new metadata text should be
    * displayed initially with offset 0, no matter what the current playback
    * duration is.
    */
  private var lastPlaybackDuration = InitialPlaybackDuration

  /**
    * Stores the last radio source for which a playback progress event has been
    * received. This is needed to reset the last playback duration if playback
    * switches to another source.
    */
  private var lastPlayedSource: RadioSource = _

  /**
    * A function to update the field with the current metadata based on elapsed
    * playback time.
    */
  private var metadataTimeFunc = generateMetadataTimeFunc(UnsupportedMetadataText)

  /**
    * A flag that indicates that radio sources are currently updated. In this
    * mode change events from the combo box have to be ignored.
    */
  private var sourcesUpdating = false

  /** A flag whether a radio source is currently played. */
  private var playbackActive = false

  /**
    * @inheritdoc The controller is registered at the combo box with radio
    *             sources. This implementation switches to the currently
    *             selected radio source.
    */
  override def elementChanged(formChangeEvent: FormChangeEvent): Unit = {
    if (!sourcesUpdating) {
      val source = comboSources.getData.asInstanceOf[RadioSource]
      if (source != null) {
        lastPlaybackDuration = InitialPlaybackDuration
        metadataTimeFunc = generateMetadataTimeFunc(UnsupportedMetadataText)
        metadataText.setText(UnsupportedMetadataText)

        statusLineController.updateCurrentSource(source)
        player.switchToRadioSource(source)
        val nextSource = radioSources find (t => t._2 == source)
        nextSource foreach storeCurrentSource
      }
    }
  }

  /**
    * Starts radio playback. This method is typically invoked in reaction on
    * the start playback action.
    */
  def startPlayback(): Unit = {
    player.startPlayback()
    enablePlaybackActions(isPlaying = true)
    playbackActive = true
  }

  /**
    * Stops radio playback. This method is typically invoked in reaction on
    * the stop playback action.
    */
  def stopPlayback(): Unit = {
    player.stopPlayback()
    enablePlaybackActions(isPlaying = false)
    playbackActive = false
  }

  /**
    * Notifies this controller about a change of the playback time for the
    * current radio source. This method is invoked when a corresponding event
    * from the player is received. It must be called in the event thread.
    *
    * @param source the source that is currently played
    * @param time   the updated playback time
    */
  def playbackTimeProgress(source: RadioSource, time: FiniteDuration): Unit = {
    lastPlaybackDuration = time
    if (source != lastPlayedSource) {
      lastPlayedSource = source
      metadataTimeFunc = generateMetadataTimeFunc(lastMetadataText)
    }
    metadataText.setText(metadataTimeFunc(time))
  }

  /**
    * Notifies this controller about a metadata event. This causes the field
    * that displays metadata about the current song to be updated. This method
    * must be called in the event thread.
    *
    * @param event the event about updated metadata
    */
  def metadataChanged(event: RadioMetadataEvent): Unit = {
    val data = event.metadata match {
      case MetadataNotSupported => UnsupportedMetadataText
      case c: CurrentMetadata =>
        log.info("Radio stream metadata: \"{}\".", c.data)
        c.title
    }

    if (data != lastMetadataText) {
      lastMetadataText = data
      metadataTimeFunc = generateMetadataTimeFunc(data)
    }
  }

  /**
    * Returns the function for handling messages published on the message bus.
    * Here the message about the initialized radio player is handled. That way,
    * the application passes the radio player to the controller.
    *
    * @return the message handling function
    */
  override def receive: Receive = {
    case RadioPlayerInitialized(triedRadioPlayer) =>
      triedRadioPlayer match {
        case Success(value) =>
          player = value

          sourcesUpdating = true
          try {
            player.initRadioSourceConfig(playerConfig.sourceConfig)
            player.initMetadataConfig(playerConfig.metadataConfig)
            radioSources = updateSourceCombo(playerConfig.sourceConfig)
            enableAction(ActionStartPlayback, enabled = false)
            enableAction(ActionStopPlayback, enabled = radioSources.nonEmpty)

            startPlaybackIfPossible(radioSources, playerConfig.initialDelay.millis)
          } finally sourcesUpdating = false

        case Failure(exception) =>
          log.error("Initialization of radio player failed.", exception)
          statusLineController.playerInitializationFailed()
      }
  }

  /**
    * Updates the combo box with the radio sources from the configuration.
    *
    * @return the list of currently available radio sources
    */
  private def updateSourceCombo(srcConfig: RadioSourceConfig): Seq[(String, RadioSource)] = {
    clearSourceCombo()
    val sources = srcConfig.namedSources
    sources.zipWithIndex foreach { t => comboSources.addItem(t._2, t._1._1, t._1._2) }
    sources
  }

  /**
    * Clears the combo box with radio sources.
    */
  private def clearSourceCombo(): Unit = {
    @tailrec def removeComboEntry(idx: Int): Unit = {
      if (idx >= 0) {
        comboSources removeItem idx
        removeComboEntry(idx - 1)
      }
    }

    removeComboEntry(comboSources.getListModel.size() - 1)
  }

  /**
    * Starts initial radio playback if sources are available. The source to be
    * played is obtained from the configuration; it this fails, playback starts
    * with the first available source.
    *
    * @param sources the list of available sources
    * @param delay   a delay for switching to the radio source
    */
  private def startPlaybackIfPossible(sources: Seq[(String, RadioSource)],
                                      delay: FiniteDuration): Unit = {
    val optCurrentSource = readCurrentSourceFromConfig(sources) orElse sources.headOption
    optCurrentSource foreach { s =>
      player.switchToRadioSource(s._2)
      player.startPlayback()
      statusLineController.updateCurrentSource(s._2)
      comboSources setData s._2
      storeCurrentSource(s)
      playbackActive = true
    }
  }

  /**
    * Stores the current radio source in the configuration, so that playback
    * can continue when restarting the application.
    *
    * @param src information about the current source
    */
  private def storeCurrentSource(src: (String, RadioSource)): Unit = {
    userConfig.setProperty(KeyCurrentSource, src._1)
  }

  /**
    * Reads information about the current source from the configuration and
    * matches it against the existing sources. If a current source is defined
    * in the configuration that also exists, it is returned. Otherwise, result
    * is ''None''.
    *
    * @param sources the available sources
    * @return the current source
    */
  private def readCurrentSourceFromConfig(sources: Seq[(String, RadioSource)]): Option[(String,
    RadioSource)] = {
    Option(userConfig.getString(KeyCurrentSource)) flatMap { name =>
      sources find (_._1 == name)
    }
  }

  /**
    * Sets the state of an action.
    *
    * @param name    the action's name
    * @param enabled the new enabled state
    */
  private def enableAction(name: String, enabled: Boolean): Unit = {
    actionStore.getAction(name) setEnabled enabled
  }

  /**
    * Enables the action that control playback based on the current playing
    * state.
    *
    * @param isPlaying flag whether playback is currently active
    */
  private def enablePlaybackActions(isPlaying: Boolean): Unit = {
    enableAction(ActionStartPlayback, !isPlaying)
    enableAction(ActionStopPlayback, isPlaying)
  }

  /**
    * Returns a function to update the metadata field when a playback progress
    * event occurs. If the metadata text length exceeds a configurable
    * threshold, the text is rotated.
    *
    * @param text the current metadata text
    * @return the function to update the text based on playback time
    */
  private def generateMetadataTimeFunc(text: String): TextTimeFunctions.TextTimeFunc =
    TextTimeFunctions.rotateText(text = text,
      maxLen = playerConfig.metaMaxLen,
      scale = playerConfig.metaRotateSpeed,
      relativeTo = lastPlaybackDuration)
}
