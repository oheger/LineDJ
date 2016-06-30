/*
 * Copyright 2015-2016 The Developers Team.
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

import de.oliver_heger.linedj.player.engine.RadioSource
import de.oliver_heger.linedj.player.engine.facade.RadioPlayer
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.model.ListComponentHandler
import net.sf.jguiraffe.gui.builder.event.{FormChangeEvent, FormChangeListener}
import net.sf.jguiraffe.gui.builder.window.{WindowEvent, WindowListener}
import org.apache.commons.configuration.Configuration

import scala.annotation.tailrec

object RadioController {
  /** Configuration key for the current source. */
  private val KeyCurrentSource = "radio.current"

  /** The name of the start playback action. */
  private val ActionStartPlayback = "startPlaybackAction"

  /** The name of the stop playback action. */
  private val ActionStopPlayback = "stopPlaybackAction"
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
  * @param player       the radio player to be managed
  * @param config       the current configuration (containing radio sources)
  * @param actionStore  the object for accessing actions
  * @param comboSources the combo box with the radio sources
  */
class RadioController(val player: RadioPlayer, val config: Configuration, actionStore: ActionStore,
                      comboSources: ListComponentHandler,
                      val configFactory: Configuration => RadioSourceConfig)
  extends WindowListener with FormChangeListener {

  def this(player: RadioPlayer, config: Configuration, actionStore: ActionStore,
    comboSources: ListComponentHandler) = this(player, config, actionStore, comboSources,
    RadioSourceConfig.apply)

  import RadioController._

  /** Stores the currently available radio sources. */
  private var radioSources = Seq.empty[(String, RadioSource)]

  /**
    * A flag that indicates that radio sources are currently updated. In this
    * mode change events from the combo box have to be ignored.
    */
  private var sourcesUpdating = false

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
    sourcesUpdating = true
    try {
      val srcConfig = configFactory(config)
      player initSourceExclusions srcConfig.exclusions
      radioSources = updateSourceCombo(srcConfig)
      enableAction(ActionStartPlayback, enabled = false)
      enableAction(ActionStopPlayback, enabled = radioSources.nonEmpty)

      startPlaybackIfPossible(radioSources)
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
        player.switchToSource(source)

        val nextSource = radioSources find(t => t._2 == source)
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
  }

  /**
    * Stops radio playback. This method is typically invoked in reaction on
    * the stop playback action.
    */
  def stopPlayback(): Unit = {
    player.stopPlayback()
    enablePlaybackActions(isPlaying = false)
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
    * Starts playback of a radio source if sources are available. The source to
    * be played is obtained from the configuration; it this fails, playback
    * starts with the first available source.
    *
    * @param sources the list of available sources
    */
  private def startPlaybackIfPossible(sources: Seq[(String, RadioSource)]): Unit = {
    val currentSource = readCurrentSourceFromConfig(sources) orElse sources.headOption
    currentSource foreach { s =>
      player switchToSource s._2
      player.startPlayback()
      comboSources setData s._2
      storeCurrentSource(s)
    }
  }

  /**
    * Stores the current radio source in the configuration, so that playback
    * can continue when restarting the application.
    *
    * @param src information about the current source
    */
  private def storeCurrentSource(src: (String, RadioSource)): Unit = {
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
