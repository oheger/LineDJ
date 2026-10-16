/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio.playlist.persistence

import de.oliver_heger.linedj.shared.config.ConfigExtensions.toDuration
import org.apache.commons.configuration.Configuration

import java.nio.file.{Path, Paths}
import scala.concurrent.duration.*
import scala.util.{Success, Try}

object PlaylistHandlerConfig:
  /** Prefix of all configuration properties used by this module. */
  final val ConfigPrefix = "audio.playlist.persistence."

  /**
    * The configuration property for the path to the persistent playlist. The
    * songs contained in the current playlist are stored here.
    */
  final val PropPlaylistPath: String = ConfigPrefix + "path"

  /**
    * Configuration property for the path to the file with position
    * information. This file contains data like the current index in the
    * playlist. It is written in regular intervals and when there is a change
    * in the state of the playlist.
    */
  final val PropPositionPath: String = ConfigPrefix + "pathPosition"

  /**
    * Configuration property for the auto save interval for the position file
    * (a number with a unit). Per default, the position file is written each
    * time the index in the current playlist changes. By specifying an auto
    * save interval, it is written after this amount of time has been played.
    * This increases the likelihood that playback starts at the same position
    * even if the application crashes. This property is optional; if it is
    * undefined, no additional save operations are executed.
    */
  final val PropAutoSaveInterval: String = ConfigPrefix + "autoSave"

  /**
    * Configuration property for the maximum size of a persistent playlist
    * file (in bytes). This is used to prevent OutOfMemory errors if the
    * component is passed huge files. This property is optional; if it is
    * undefined, no size restriction is applied.
    */
  final val PropMaxFileSize: String = ConfigPrefix + "maxFileSize"

  /**
    * Configuration property defining a timeout for a shutdown operation (a
    * number with a unit). When the playlist handler service is deactivated it
    * may be necessary to write latest changes on the playlist state to disk.
    * The service has to wait until this is done. With this property the
    * maximum time the service will wait for the completion of the save
    * operation can be specified. This property is optional; if unspecified, a
    * default timeout value is set.
    */
  final val PropShutdownTimeout: String = ConfigPrefix + "shutdownTimeout"

  /**
    * The prefix for the properties that define the intervals for the check 
    * whether all songs in the playlist are available in the archive.
    */
  final val CheckPlaylistPrefix = ConfigPrefix + "availabilityCheck."

  /**
    * Configuration property defining the minimum backoff when checking whether
    * the songs in the playlist are available in the archive. This is a number
    * with a unit.
    */
  final val PropCheckPlaylistMinBackoff = CheckPlaylistPrefix + "minBackoff"

  /**
    * Configuration property defining the maximum backoff when checking whether
    * the songs in the playlist are available in the archive. This is a number
    * * with a unit.
    */
  final val PropCheckPlaylistMaxBackoff = CheckPlaylistPrefix + "maxBackoff"

  /**
    * Configuration property defining the factor by which the interval to check
    * for the availability of songs in the archive is to be incremented.
    */
  final val PropCheckPlaylistFactor = CheckPlaylistPrefix + "factor"

  /**
    * Default value for the _shutdownTimeout_ property.
    */
  final val DefaultShutdownTimeout: FiniteDuration = 5.seconds

  /**
    * Default value for the minimum backoff when checking for the availability
    * of songs in the archive.
    */
  final val DefaultCheckPlaylistMinBackoff = 1.second

  /**
    * Default value for the maximum backoff when checking for the availability
    * of songs in the archive.
    */
  final val DefaultCheckPlaylistMaxBackoff = 30.seconds

  /**
    * Default value for the increment factor when checking for the availability
    * of songs in the archive.
    */
  final val DefaultCheckPlaylistFactor = 1.25

  /**
    * Tries to create a [[PlaylistHandlerConfig]] object from the settings
    * defined in the provided [[Configuration]] object. If all mandatory
    * properties are defined and are valid, a _Success_ instance is returned.
    * Otherwise, an error message is available.
    *
    * @param conf the [[Configuration]] object
    * @return a [[Try]] with the extracted configuration data
    */
  def apply(conf: Configuration): Try[PlaylistHandlerConfig] =
    for
      playlistPath <- pathProperty(conf, PropPlaylistPath)
      positionPath <- pathProperty(conf, PropPositionPath)
      maxSize <- intProperty(conf, PropMaxFileSize, Integer.MAX_VALUE)
      autoSave <- durationProperty(conf, PropAutoSaveInterval, Integer.MAX_VALUE.seconds)
      shutdownTimeout <- durationProperty(conf, PropShutdownTimeout, DefaultShutdownTimeout)
      minBackoff <- durationProperty(conf, PropCheckPlaylistMinBackoff, DefaultCheckPlaylistMinBackoff)
      maxBackoff <- durationProperty(conf, PropCheckPlaylistMaxBackoff, DefaultCheckPlaylistMaxBackoff)
      factor <- Success(conf.getDouble(PropCheckPlaylistFactor, DefaultCheckPlaylistFactor))
    yield
      PlaylistHandlerConfig(
        pathPlaylist = playlistPath,
        pathPosition = positionPath,
        maxFileSize = maxSize,
        autoSaveInterval = autoSave,
        shutdownTimeout = shutdownTimeout,
        checkPlaylistMinBackoff = minBackoff,
        checkPlaylistMaxBackoff = maxBackoff,
        checkPlaylistFactor = factor
      )

  /**
    * Returns a [[Try]] with the value of a configuration property which 
    * defines a [[Path]].
    *
    * @param conf the [[Configuration]] object
    * @param key  the key of the desired property
    * @return a [[Try]] with the extracted value
    */
  private def pathProperty(conf: Configuration, key: String): Try[Path] =
    Try(Paths.get(conf.getString(key)))

  /**
    * Returns a [[Try]] with the value of a configuration property of type
    * _Int_.
    *
    * @param conf    the [[Configuration]] object
    * @param key     the key of the desired property
    * @param default the default value if the property is missing
    * @return a [[Try]] with the extracted value
    */
  private def intProperty(conf: Configuration, key: String, default: Int): Try[Int] =
    Try(conf.getInt(key, default))

  /**
    * Returns a [[Try]] with the value of a configuration property of type
    * duration.
    *
    * @param conf    the [[Configuration]] object
    * @param key     the key of the desired property
    * @param default the default value if the property is missing
    * @return a [[Try]] with the extracted value
    */
  private def durationProperty(conf: Configuration, key: String, default: FiniteDuration): Try[FiniteDuration] =
    if conf.containsKey(key) then
      conf.getString(key).toDuration
    else
      Success(default)
end PlaylistHandlerConfig

/**
  * A class storing all configuration settings supported by the playlist
  * handler module.
  *
  * @param pathPlaylist            path to the playlist file
  * @param pathPosition            path to the position file
  * @param maxFileSize             the maximum file size (in bytes)
  * @param autoSaveInterval        the auto-save interval
  * @param shutdownTimeout         the shutdown timeout
  * @param checkPlaylistMinBackoff min backoff to check for songs availability
  * @param checkPlaylistMaxBackoff max backoff to check for songs availability
  * @param checkPlaylistFactor     increment factor for playlist checks
  */
final case class PlaylistHandlerConfig(pathPlaylist: Path,
                                       pathPosition: Path,
                                       maxFileSize: Int,
                                       autoSaveInterval: FiniteDuration,
                                       shutdownTimeout: FiniteDuration,
                                       checkPlaylistMinBackoff: FiniteDuration,
                                       checkPlaylistMaxBackoff: FiniteDuration,
                                       checkPlaylistFactor: Double)
