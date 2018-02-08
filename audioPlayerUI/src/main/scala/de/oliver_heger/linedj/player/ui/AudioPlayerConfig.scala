/*
 * Copyright 2015-2018 The Developers Team.
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

import org.apache.commons.configuration.Configuration

object AudioPlayerConfig {
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
    * property value is an integer interpreted as divisor for the playback
    * time. A value of 1 means that for each second 1 letter is scrolled; 2
    * means that every 2 seconds one letter is scrolled, etc.
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
    * automatically when a playback is available. If this property is set to
    * '''true''', a change in the current playlist automatically causes
    * playback to start if it is not yet enabled. The default value is
    * '''false'''.
    */
  val PropAutoStartPlayback: String = ConfigPrefix + "autoStartPlayback"

  /**
    * Default value for the ''rotationSpeed'' property. Per default, the
    * scrolling speed is one character per playback second.
    */
  val DefRotationSpeed = 1

  /**
    * Default value for the ''skipBackwardsThreshold'' property.
    */
  val DefSkipBackwardsThreshold = 5

  /**
    * Returns a new instance of ''AudioPlayerConfig'' that is initialized from
    * the specified ''Configuration'' object.
    *
    * @param config the ''Configuration''
    * @return the new ''AudioPlayerConfig'' instance
    */
  def apply(config: Configuration): AudioPlayerConfig =
    AudioPlayerConfig(config.getInt(PropMaxFieldSize, Integer.MAX_VALUE),
      config.getInt(PropRotationSpeed, DefRotationSpeed),
      config.getInt(PropSkipBackwardsThreshold, DefSkipBackwardsThreshold),
      config.getBoolean(PropAutoStartPlayback, false))
}

/**
  * A class allowing convenient access to all configuration properties
  * supported by the audio player application.
  *
  * @param maxUIFieldSize         the maximum size of fields in the UI
  * @param rotationSpeed          the rotation speed
  * @param skipBackwardsThreshold threshold for skipping backwards
  * @param autoStartPlayback      the auto start playback flag
  */
case class AudioPlayerConfig(maxUIFieldSize: Int, rotationSpeed: Int,
                             skipBackwardsThreshold: Int, autoStartPlayback: Boolean)
