/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.playlist.persistence

import java.nio.file.{Path, Paths}

import org.apache.commons.configuration.Configuration

import scala.concurrent.duration._
import scala.util.Try

object PlaylistHandlerConfig:
  /** Prefix of all configuration properties used by this module. */
  val ConfigPrefix = "audio.playlist.persistence."

  /**
    * The configuration property for the path to the persistent playlist. The
    * songs contained in the current playlist are stored here.
    */
  val PropPlaylistPath: String = ConfigPrefix + "path"

  /**
    * Configuration property for the path to the file with position
    * information. This file contains data like the current index in the
    * playlist. It is written in regular intervals and when there is a change
    * in the state of the playlist.
    */
  val PropPositionPath: String = ConfigPrefix + "pathPosition"

  /**
    * Configuration property for the auto save interval for the position file
    * (in seconds). Per default, the position file is written each time the
    * index in the current playlist changes. By specifying an auto save
    * interval, it is written after this amount of time has been played. This
    * increases the likelihood that playback starts at the same position even
    * if the application crashes. This property is optional; if it is
    * undefined, no additional save operations are executed.
    */
  val PropAutoSaveInterval: String = ConfigPrefix + "autoSave"

  /**
    * Configuration property for the maximum size of a persistent playlist
    * file (in bytes). This is used to prevent OutOfMemory errors if the
    * component is passed huge files. This property is optional; if it is
    * undefined, no size restriction is applied.
    */
  val PropMaxFileSize: String = ConfigPrefix + "maxFileSize"

  /**
    * Configuration property defining a timeout for a shutdown operation (in
    * milliseconds). When the playlist handler service is deactivated it may be
    * necessary to write latest changes on the playlist state to disk. The
    * service has to wait until this is done. With this property the maximum
    * time the service will wait for the completion of the save operation can
    * be specified. This property is optional; if unspecified, a default
    * timeout value is set.
    */
  val PropShutdownTimeout: String = ConfigPrefix + "shutdownTimeout"

  /**
    * Default value for the ''shutdownTimeout'' property.
    */
  val DefaultShutdownTimeout: FiniteDuration = 5.seconds

  /**
    * Tries to create a [[PlaylistHandlerConfig]] object from the settings
    * defined in the provided ''Configuration'' object. If all mandatory
    * properties are defined and valid, a ''Success'' instance is returned.
    * Otherwise, an error message is available.
    *
    * @param conf the ''Configuration'' object
    * @return a ''Try'' with the extracted configuration data
    */
  def apply(conf: Configuration): Try[PlaylistHandlerConfig] = Try:
    PlaylistHandlerConfig(Paths get conf.getString(PropPlaylistPath),
      Paths get conf.getString(PropPositionPath),
      conf.getInt(PropMaxFileSize, Integer.MAX_VALUE),
      conf.getInt(PropAutoSaveInterval, Integer.MAX_VALUE).seconds,
      conf.getLong(PropShutdownTimeout, DefaultShutdownTimeout.toMillis).millis)

/**
  * A class storing all configuration settings supported by the playlist
  * handler module.
  *
  * @param pathPlaylist     path to the playlist file
  * @param pathPosition     path to the position file
  * @param maxFileSize      the maximum file size (in bytes)
  * @param autoSaveInterval the auto-save interval
  * @param shutdownTimeout  the shutdown timeout
  */
case class PlaylistHandlerConfig(pathPlaylist: Path, pathPosition: Path,
                                 maxFileSize: Int, autoSaveInterval: FiniteDuration,
                                 shutdownTimeout: FiniteDuration)
