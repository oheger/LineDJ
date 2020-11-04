/*
 * Copyright 2015-2020 The Developers Team.
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

import de.oliver_heger.linedj.platform.ui.DurationTransformer
import de.oliver_heger.linedj.player.engine.{RadioSource, RadioSourceErrorEvent}
import de.oliver_heger.linedj.player.engine.facade.RadioPlayer
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.WidgetHandler
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, StaticTextHandler}
import net.sf.jguiraffe.gui.builder.event.{FormChangeEvent, FormChangeListener}
import net.sf.jguiraffe.gui.builder.window.{WindowEvent, WindowListener}
import net.sf.jguiraffe.resources.Message
import org.apache.commons.configuration.Configuration
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.concurrent.duration._

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

  /** Configuration key for the initial delay. */
  private val KeyInitialDelay = ConfigKeyPrefix + "initialDelay"

  /** Configuration key prefix for error recovery keys. */
  private val RecoveryKeyPrefix = ConfigKeyPrefix + "error.recovery."

  /** Configuration key for the error recovery time. */
  private val KeyRecoveryTime = RecoveryKeyPrefix + "time"

  /** Configuration key for the minimum failed sources before recovery. */
  private val KeyRecoveryMinFailures = RecoveryKeyPrefix + "minFailedSources"

  /** Default time interval for error recovery (in seconds). */
  private val DefaultRecoveryTime = 600L

  /** Default minimum number of dysfunctional sources before recovery. */
  private val DefaultMinFailuresForRecovery = 1

  /** The name of the start playback action. */
  private val ActionStartPlayback = "startPlaybackAction"

  /** The name of the stop playback action. */
  private val ActionStopPlayback = "stopPlaybackAction"

  /** Resource key for the status text for normal playback. */
  private val ResKeyStatusPlaybackNormal = "txt_status_playback"

  /** Resource key for the status text for replacement playback. */
  private val ResKeyStatusPlaybackReplace = "txt_status_replacement"
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
  * @param player                the radio player to be managed
  * @param config                the current configuration (containing radio sources)
  * @param applicationContext    the application context
  * @param actionStore           the object for accessing actions
  * @param comboSources          the combo box with the radio sources
  * @param statusText            handler for the status line
  * @param playbackTime          handler for the field with the playback time
  * @param errorIndicator        handler to the field that displays error state
  * @param errorHandlingStrategy the ''ErrorHandlingStrategy''
  * @param configFactory         the factory for creating a radio source configuration
  */
class RadioController(val player: RadioPlayer, val config: Configuration,
                      applicationContext: ApplicationContext, actionStore: ActionStore,
                      comboSources: ListComponentHandler, statusText: StaticTextHandler,
                      playbackTime: StaticTextHandler,
                      errorIndicator: WidgetHandler,
                      errorHandlingStrategy: ErrorHandlingStrategy,
                      val configFactory: Configuration => RadioSourceConfig)
  extends WindowListener with FormChangeListener {

  def this(player: RadioPlayer, config: Configuration, applicationContext: ApplicationContext,
           actionStore: ActionStore, comboSources: ListComponentHandler,
           statusText: StaticTextHandler, playbackTime: StaticTextHandler,
           errorIndicator: WidgetHandler, errorHandlingStrategy: ErrorHandlingStrategy) =
    this(player, config, applicationContext, actionStore, comboSources, statusText, playbackTime,
      errorIndicator, errorHandlingStrategy, RadioSourceConfig.apply)

  import RadioController._

  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

  /** Stores the currently available radio sources. */
  private var radioSources = Seq.empty[(String, RadioSource)]

  /** The configuration for the error handling strategy. */
  private var errorHandlingConfig: ErrorHandlingStrategy.Config = _

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

  /**
    * The time (in seconds) when a recovery from an error should be
    * attempted. This value is read from the player configuration.
    */
  private var errorRecoveryTimeField = DefaultRecoveryTime

  /**
    * The minimum number of sources that must be marked as dysfunctional before
    * a recovery from an error is attempted.
    */
  private var minFailedSourcesForRecoveryField = DefaultMinFailuresForRecovery

  /**
    * A flag that indicates that radio sources are currently updated. In this
    * mode change events from the combo box have to be ignored.
    */
  private var sourcesUpdating = false

  /** A flag whether a radio source is currently played. */
  private var playbackActive = false

  /**
    * Returns the time (in seconds) when a recovery from an error should be
    * attempted. This value is read from the player configuration.
    *
    * @return the recovery time
    */
  def errorRecoveryTime: Long = errorRecoveryTimeField

  /**
    * Returns the minimum number of sources that must be marked as
    * dysfunctional before a recovery from an error is attempted. Switching
    * back to the original source makes only sense if there was a general
    * network problem which is now fixed. Then we expect that multiple sources
    * have been marked as dysfunctional. If there are only a few sources
    * affected, this may indicate a (more permanent) problem with these
    * sources, e.g. an incorrect URL. In this case, switching back to such a
    * source is likely to fail again; and we should not interrupt playback
    * every time the recovery interval is reached.
    *
    * @return number of dysfunctional sources before recovery
    */
  def minFailedSourcesForRecovery: Int = minFailedSourcesForRecoveryField

  override def windowDeiconified(windowEvent: WindowEvent): Unit = {}

  override def windowClosing(windowEvent: WindowEvent): Unit = {}

  override def windowClosed(windowEvent: WindowEvent): Unit = {}

  override def windowActivated(windowEvent: WindowEvent): Unit = {}

  override def windowDeactivated(windowEvent: WindowEvent): Unit = {}

  override def windowIconified(windowEvent: WindowEvent): Unit = {}

  /**
    * @inheritdoc This implementation reads configuration settings and
    *             starts playback if radio sources are defined.
    */
  override def windowOpened(windowEvent: WindowEvent): Unit = {
    errorIndicator setVisible false
    sourcesUpdating = true
    try {
      val srcConfig = configFactory(config)
      errorHandlingConfig = ErrorHandlingStrategy.createConfig(config, srcConfig)
      errorRecoveryTimeField = config.getLong(KeyRecoveryTime, DefaultRecoveryTime)
      minFailedSourcesForRecoveryField = config.getInt(KeyRecoveryMinFailures,
        DefaultMinFailuresForRecovery)

      player.initSourceExclusions(srcConfig.exclusions, srcConfig.ranking)
      radioSources = updateSourceCombo(srcConfig)
      enableAction(ActionStartPlayback, enabled = false)
      enableAction(ActionStopPlayback, enabled = radioSources.nonEmpty)

      startPlaybackIfPossible(radioSources,
        config.getInt(KeyInitialDelay, DefaultInitialDelay).millis)
    } finally sourcesUpdating = false
  }

  /**
    * @inheritdoc The controller is registered at the combo box with radio
    *             sources. This implementation switches to the currently
    *             selected radio source.
    */
  override def elementChanged(formChangeEvent: FormChangeEvent): Unit = {
    if (!sourcesUpdating) {
      val source = comboSources.getData.asInstanceOf[RadioSource]
      if (source != null) {
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
  def playbackTimeProgress(time: Long): Unit = {
    playbackTime setText DurationTransformer.formatDuration(time * 1000)
    if (shouldRecover(time)) {
      recoverFromError()
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
      val (action, nextState) = errorHandlingStrategy.handleError(errorHandlingConfig,
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
    * Determines the radio source to be played. This is either a replacement
    * source or the current source.
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
    * @param time the playback time of the current source (in seconds)
    * @return a flag whether recovery should be done now
    */
  private def shouldRecover(time: Long): Boolean =
    ErrorHandlingStrategy.NoError != errorState &&
      time >= errorRecoveryTime &&
      errorState.numberOfErrorSources >= minFailedSourcesForRecovery

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
    config.setProperty(KeyCurrentSource, src._1)
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
    Option(config.getString(KeyCurrentSource)) flatMap { name =>
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
}
