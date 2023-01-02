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

package de.oliver_heger.linedj.player.ui

import java.util.Locale
import de.oliver_heger.linedj.platform.audio.AudioPlayerState
import de.oliver_heger.linedj.player.ui.AudioPlayerConfig.AutoStartMode
import org.apache.commons.configuration.Configuration
import org.apache.logging.log4j.LogManager

object AudioPlayerConfig {
  /** The logger. */
  private val Log = LogManager.getLogger(classOf[AudioPlayerConfig])

  /** Common prefix for all configuration keys. */
  val ConfigPrefix = "audio.ui."

  /**
    * Configuration property for the maximum size of fields in the UI. If a
    * text to be displayed exceeds this value, it is "rotated"; i.e. it is
    * scrolled through the field, so that the full content can be seen.
    */
  val PropMaxFieldSize: String = ConfigPrefix + "maxFieldSize"

  /**
    * Configuration property defining the speed for scrolling in UI fields. The
    * property value is a double interpreted as factor for the playback time. A
    * value of 1 means that for each second 1 letter is scrolled; 2 means that
    * every second two letters are scrolled (one every 500 millis), etc. A
    * change of the playback time is reported every 100 millis; so a factor of
    * 10 is the maximum.
    */
  val PropRotationSpeed: String = ConfigPrefix + "rotationSpeed"

  /**
    * Configuration property that determines the behavior of the move backwards
    * action. The property defines a threshold in seconds. If the playback time
    * of the current song is bigger than this threshold, the backwards action
    * causes this song to be played again (from the start). Otherwise, playback
    * starts with the previous song in the playlist.
    */
  val PropSkipBackwardsThreshold: String = ConfigPrefix + "skipBackwardsThreshold"

  /**
    * Configuration property that determines whether playback should start
    * automatically when a playlist is available. The value of this property is
    * a string matching one of the ''AutoStartModeName'' constants (ignoring
    * case). The auto start mode referenced determines how the player reacts
    * when a playlist is set. If this property is undefined or has an invalid
    * value, [[AutoStartNever]] is set.
    */
  val PropAutoStartPlayback: String = ConfigPrefix + "autoStartPlayback"

  /**
    * Default value for the ''rotationSpeed'' property. Per default, the
    * scrolling speed is one character per playback second.
    */
  val DefRotationSpeed = 1.0

  /**
    * Default value for the ''skipBackwardsThreshold'' property.
    */
  val DefSkipBackwardsThreshold = 5

  /**
    * Name of the auto start mode ''always''. In this mode, playback starts as
    * soon as a non-empty playlist is available.
    */
  val AutoStartModeNameAlways = "always"

  /**
    * Name of the auto start mode ''if closed''. In this mode, playback starts
    * when a non-empty playlist is available that has been closed.
    */
  val AutoStartModeNameIfClosed = "closed"

  /**
    * Name of the auto start mode ''never''. In this mode, auto start of
    * playback is disabled. This is the default mode; it is set if the
    * property for the auto start mode is undefined or invalid.
    */
  val AutoStartModeNameNever = "never"

  /**
    * A trait defining auto start functionality.
    *
    * When a playlist becomes available it is possible to start playback
    * automatically if specific criteria are fulfilled. The different modes for
    * auto start are represented by implementations of this trait.
    */
  sealed trait AutoStartMode {
    /**
      * Checks whether the passed in audio player state allows auto start of
      * playback. This method is invoked by the audio player controller if a
      * playlist is available.
      *
      * @param state the audio player state
      * @return a flag whether playback can start now
      */
    def canStartPlayback(state: AudioPlayerState): Boolean
  }

  /**
    * A special auto start mode that triggers start of playback as soon as a
    * playlist is available.
    */
  object AutoStartAlways extends AutoStartMode {
    override def canStartPlayback(state: AudioPlayerState): Boolean = true
  }

  /**
    * A special auto start mode that triggers start of playback if a playlist
    * is available that has been closed.
    */
  object AutoStartIfClosed extends AutoStartMode {
    override def canStartPlayback(state: AudioPlayerState): Boolean = state.playlistClosed
  }

  /**
    * A special auto start mode that disables auto start of playback.
    */
  object AutoStartNever extends AutoStartMode {
    override def canStartPlayback(state: AudioPlayerState): Boolean = false
  }

  /**
    * Returns a new instance of ''AudioPlayerConfig'' that is initialized from
    * the specified ''Configuration'' object.
    *
    * @param config the ''Configuration''
    * @return the new ''AudioPlayerConfig'' instance
    */
  def apply(config: Configuration): AudioPlayerConfig =
    AudioPlayerConfig(config.getInt(PropMaxFieldSize, Integer.MAX_VALUE),
      config.getDouble(PropRotationSpeed, DefRotationSpeed),
      config.getInt(PropSkipBackwardsThreshold, DefSkipBackwardsThreshold),
      mapAutoStartMode(config))

  /**
    * Extracts an auto start mode from the configuration.
    *
    * @param config the configuration
    * @return the auto start mode
    */
  private def mapAutoStartMode(config: Configuration): AutoStartMode = {
    val mode = config.getString(PropAutoStartPlayback,
      AutoStartModeNameNever).toLowerCase(Locale.ROOT)
    mode match {
      case AutoStartModeNameAlways => AutoStartAlways
      case AutoStartModeNameIfClosed => AutoStartIfClosed
      case AutoStartModeNameNever => AutoStartNever
      case s =>
        Log.warn("Unsupported auto start mode '{}'. Setting default.", s)
        AutoStartNever
    }
  }
}

/**
  * A class allowing convenient access to all configuration properties
  * supported by the audio player application.
  *
  * @param maxUIFieldSize         the maximum size of fields in the UI
  * @param rotationSpeed          the rotation speed
  * @param skipBackwardsThreshold threshold for skipping backwards
  * @param autoStartMode          the auto start playback mode
  */
case class AudioPlayerConfig(maxUIFieldSize: Int,
                             rotationSpeed: Double,
                             skipBackwardsThreshold: Int,
                             autoStartMode: AutoStartMode)
