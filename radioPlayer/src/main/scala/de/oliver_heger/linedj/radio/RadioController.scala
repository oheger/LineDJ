/*
 * Copyright 2015-2022 The Developers Team.
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
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.player.engine.radio._
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.WidgetHandler
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, StaticTextHandler}
import net.sf.jguiraffe.gui.builder.event.{FormChangeEvent, FormChangeListener}
import net.sf.jguiraffe.resources.Message
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

  /** Resource key for the status text for normal playback. */
  private val ResKeyStatusPlaybackNormal = "txt_status_playback"

  /** Resource key for the status text for replacement playback. */
  private val ResKeyStatusPlaybackReplace = "txt_status_replacement"

  /** Resource key for the initialization error message. */
  private val ResKeyStatusPlayerInitError = "txt_status_error"

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
  * @param userConfig            the current user configuration
  * @param applicationContext    the application context
  * @param actionStore           the object for accessing actions
  * @param comboSources          the combo box with the radio sources
  * @param statusText            handler for the status line
  * @param playbackTime          handler for the field with the playback time
  * @param metadataText          handler for the field to display metadata
  * @param errorIndicator        handler to the field that displays error state
  * @param errorHandlingStrategy the ''ErrorHandlingStrategy''
  * @param playerConfig          the configuration for the radio player
  */
class RadioController(val userConfig: Configuration,
                      applicationContext: ApplicationContext,
                      actionStore: ActionStore,
                      comboSources: ListComponentHandler,
                      statusText: StaticTextHandler,
                      playbackTime: StaticTextHandler,
                      metadataText: StaticTextHandler,
                      errorIndicator: WidgetHandler,
                      errorHandlingStrategy: ErrorHandlingStrategy,
                      val playerConfig: RadioPlayerConfig)
  extends FormChangeListener with MessageBusListener {

  def this(config: Configuration,
           applicationContext: ApplicationContext,
           actionStore: ActionStore,
           comboSources: ListComponentHandler,
           statusText: StaticTextHandler,
           playbackTime: StaticTextHandler,
           metadataText: StaticTextHandler,
           errorIndicator: WidgetHandler,
           errorHandlingStrategy: ErrorHandlingStrategy) =
    this(config, applicationContext, actionStore, comboSources, statusText, playbackTime, metadataText,
      errorIndicator, errorHandlingStrategy, RadioPlayerConfig(applicationContext.getConfiguration))

  import RadioController._

  /** The logger. */
  private val log = LogManager.getLogger(getClass)

  /** The function to generate the playback time text. */
  private val playbackTimeFunc = TextTimeFunctions.formattedTime()

  /** The player managed by this controller. */
  private var player: RadioPlayer = _

  /** Stores the currently available radio sources. */
  private var radioSources = Seq.empty[(String, RadioSource)]

  /** Stores the current radio source. */
  private var currentSource: RadioSource = _

  /**
    * The source that is currently played. This is not necessary the current
    * source, e.g. if a replacement source is currently active.
    */
  private var playbackSource: RadioSource = _

  /**
    * Stores a replacement source for the current source. This field is defined
    * while the current source is in a forbidden timeslot.
    */
  private var replacementSource: Option[RadioSource] = None

  /** Stores the current error state. */
  private var errorState = ErrorHandlingStrategy.NoError

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
    * Returns the [[RadioPlayer]] managed by this controller. Note that result
    * can be '''null''' if the player has not yet been initialized.
    *
    * @return the managed [[RadioPlayer]]
    */
  def radioPlayer: RadioPlayer = player

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

        if (playbackActive)
          player.playSource(source, makeCurrent = true)
        else {
          player.makeToCurrentSource(source)
        }

        val nextSource = radioSources find (t => t._2 == source)
        nextSource foreach storeCurrentSource
        resetErrorState()
      }
    }
  }

  /**
    * Starts radio playback. This method is typically invoked in reaction on
    * the start playback action.
    */
  def startPlayback(): Unit = {
    player.playSource(sourceToBePlayed, makeCurrent = false)
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
    * Notifies this controller that playback of the specified radio source has
    * just started. This method is typically invoked in reaction of a player
    * event. The controller updates the UI. It must be called in the event
    * thread.
    *
    * @param src the source that is played
    */
  def radioSourcePlaybackStarted(src: RadioSource): Unit = {
    statusText setText generateStatusText(src)
    playbackSource = currentSource
  }

  /**
    * Notifies this controller about a change of the playback time for the
    * current radio source. This method is invoked when a corresponding event
    * from the player is received. It must be called in the event thread.
    *
    * @param time the updated playback time
    */
  def playbackTimeProgress(time: FiniteDuration): Unit = {
    playbackTime setText playbackTimeFunc(time)
    lastPlaybackDuration = time
    metadataText.setText(metadataTimeFunc(time))

    if (shouldRecover(time)) {
      recoverFromError()
    }
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
      metadataText setText metadataTimeFunc(lastPlaybackDuration)
    }
  }

  /**
    * Notifies this controller that there was an error when playing a radio
    * source. This will trigger an invocation of the error handling
    * strategy. It must be invoked in the event thread.
    *
    * @param error the error event
    */
  def playbackError(error: RadioSourceErrorEvent): Unit = {
    log.warn("Received playback error event {}!", error)
    if (playbackSource != null) {
      errorIndicator setVisible true
      val (action, nextState) = errorHandlingStrategy.handleError(playerConfig,
        errorState, error, currentSource)
      action(player)
      errorState = nextState
      playbackSource = null
    }
  }

  /**
    * Notifies this controller that there was an error when creating the
    * playback context for the current source. This has to be handled in the
    * same way as a playback error. Note that both errors may occur for the
    * same source; only one should be handled. This method must be invoked in
    * the event thread.
    */
  def playbackContextCreationFailed(): Unit = {
    log.warn("Playback context creation failed!")
    if (playbackSource != null) {
      playbackError(RadioSourceErrorEvent(playbackSource))
    }
  }

  /**
    * Notifies this controller that the current source now enters a forbidden
    * timeslot, in which a replacement source has to be played. If playback is
    * enabled, the replacement source has to be played now; otherwise, it is
    * just recorded if playback starts later.
    *
    * @param replacement the replacement source
    */
  def replacementSourceStarts(replacement: RadioSource): Unit = {
    replacementSource = Some(replacement)
    updatePlayback(replacement)
  }

  /**
    * Notifies this controller that a forbidden timeslot for the current source
    * is over. So it can be played again, if playback is active. The
    * replacement source can be removed.
    */
  def replacementSourceEnds(): Unit = {
    replacementSource = None
    updatePlayback(currentSource)
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
          errorIndicator.setVisible(false)

          sourcesUpdating = true
          try {
            player.initSourceExclusions(playerConfig.sourceConfig.exclusions, playerConfig.sourceConfig.ranking)
            radioSources = updateSourceCombo(playerConfig.sourceConfig)
            enableAction(ActionStartPlayback, enabled = false)
            enableAction(ActionStopPlayback, enabled = radioSources.nonEmpty)

            startPlaybackIfPossible(radioSources, playerConfig.initialDelay.millis)
          } finally sourcesUpdating = false

        case Failure(exception) =>
          log.error("Initialization of radio player failed.", exception)
          statusText.setText(applicationContext.getResourceText(ResKeyStatusPlayerInitError))
      }
  }

  /**
    * Determines the radio source to be played. This is either a replacement
    * source or the current source.
    *
    * @return the radio source to be played
    */
  private def sourceToBePlayed: RadioSource =
    replacementSource getOrElse currentSource

  /**
    * Updates the playback status and the UI when there is a change in the
    * source that is played related to replacement sources. If playback is
    * active, the new source needs to be played immediately; otherwise, only
    * the status line has to be updated.
    *
    * @param source the source which is currently played
    */
  private def updatePlayback(source: RadioSource): Unit = {
    if (playbackActive) {
      startPlayback()
    } else {
      statusText setText generateStatusText(source)
    }
  }

  /**
    * Updates the combo box with the radio sources from the configuration.
    *
    * @return the list of currently available radio sources
    */
  private def updateSourceCombo(srcConfig: RadioSourceConfig): Seq[(String, RadioSource)] = {
    clearSourceCombo()
    val sources = srcConfig.sources
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
    * Checks whether a recovery from error state is currently possible.
    *
    * @param time the playback time of the current source
    * @return a flag whether recovery should be done now
    */
  private def shouldRecover(time: FiniteDuration): Boolean =
    ErrorHandlingStrategy.NoError != errorState &&
      time.toSeconds >= playerConfig.errorConfig.recoveryTime &&
      errorState.numberOfErrorSources >= playerConfig.errorConfig.recoverMinFailedSources

  /**
    * Recovers from error state. The error state is reset; if necessary, the
    * player switches again to the current radio source.
    */
  private def recoverFromError(): Unit = {
    log.info("Trying to recover from error.")
    if (errorState.isAlternativeSourcePlaying(currentSource)) {
      player.playSource(currentSource, makeCurrent = false)
      log.info("Switched to radio source {}.", currentSource)
    }
    resetErrorState()
  }

  /**
    * Resets the error state for radio playback. The controller now assumes
    * that there is no error.
    */
  private def resetErrorState(): Unit = {
    errorState = ErrorHandlingStrategy.NoError
    errorIndicator setVisible false
  }

  /**
    * Generates the text for the status line when playback of a source starts.
    *
    * @param src the source which is currently played
    * @return the corresponding text for the status line
    */
  private def generateStatusText(src: RadioSource): String = {
    if (src == currentSource) {
      applicationContext.getResourceText(ResKeyStatusPlaybackNormal)
    } else {
      val srcData = radioSources.find(_._2 == src)
      applicationContext.getResourceText(new Message(null, ResKeyStatusPlaybackReplace,
        srcData.map(_._1) getOrElse src.uri))
    }
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
      player.playSource(s._2, makeCurrent = true, resetEngine = false, delay = delay)
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
    currentSource = src._2
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
