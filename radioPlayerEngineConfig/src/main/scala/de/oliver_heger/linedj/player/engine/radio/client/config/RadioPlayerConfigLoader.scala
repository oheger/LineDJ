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

package de.oliver_heger.linedj.player.engine.radio.client.config

import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.player.engine.radio.config.RadioPlayerConfig
import de.oliver_heger.linedj.player.engine.client.config.ConfigurationExtensions._
import de.oliver_heger.linedj.player.engine.client.config.PlayerConfigLoader
import org.apache.commons.configuration.Configuration

import scala.concurrent.duration.*

/**
  * A module providing functionality for loading a configuration file with
  * options for the radio player. The options that can be defined here
  * correspond to the properties of [[RadioPlayerConfig]].
  */
object RadioPlayerConfigLoader:
  /**
    * Name of the maximum eval delay property. It specifies the maximum delay
    * after which a new evaluation of the current radio source takes place,
    * even if no restrictions were found.
    */
  final val PropMaxEvalDelay = "maxEvalDelay"

  /**
    * Name of the retry failed replacement property. This property defines the
    * delay after which another attempt to find a replacement source is made.
    */
  final val PropRetryFailedReplacement = "retryFailedReplacement"

  /**
    * Name of the retry failed source property. If playback of a radio source
    * failed, the source is retried after a delay defined by this property.
    */
  final val PropRetryFailedSource = "retryFailedSource"

  /**
    * Name of the retry failed source increment property. For radio sources
    * whose playback failed multiple times, the delay for further retry
    * attempts is increased by this factor.
    */
  final val PropRetryFailedSourceIncrement = "retryFailedSourceIncrement"

  /**
    * Name of the maximum retry failed source property. This is the maximum
    * delay between retries for a failing source. If this value is reached, the
    * factor defined by [[PropRetryFailedSourceIncrement]] is no longer
    * applied.
    */
  final val PropMaxRetryFailedSource = "maxRetryFailedSource"

  /**
    * Name of the source check timeout property. This property defines the
    * maximum time a check of a failed source can take. If playback was not
    * successful or no error was received in this time, the check is aborted,
    * and the source is considered to be still in error state. This is useful
    * if the download from the source URL just hangs.
    */
  final val PropSourceCheckTimeout = "sourceCheckTimeout"

  /**
    * Name of the metadata check timeout property. It determines the maximum
    * duration of a metadata check. If no metadata that is not matched by an
    * exclusion was found within this time frame, the affected radio source
    * remains in disabled state.
    */
  final val PropMetadataCheckTimeout = "metadataCheckTimeout"

  /**
    * Name of the stream cache time property. A radio stream opened for a
    * metadata check is kept for a while in a cache in case it is reused when
    * the associated radio source is enabled and starts playback again. This
    * property defines the time how long the stream should be kept open.
    */
  final val PropStreamCacheTime = "streamCacheTime"

  /**
    * Name of the stalled playback check property. The playback guardian actor
    * checks periodically if playback is still running. If not, it resets the
    * current radio source. This property defines the interval in which such
    * checks are performed.
    */
  final val PropStalledPlaybackCheck = "stalledPlaybackCheck"

  /** The default value for the max eval delay property. */
  final val DefaultMaxEvalDelay = 1.hour

  /** The default value for the retry failed replacement property. */
  final val DefaultRetryFailedReplacement = 1.minute

  /** The default value for the retry failed source property. */
  final val DefaultRetryFailedSource = 5.seconds

  /** The default value for the retry failed source increment factor. */
  final val DefaultRetryFailedSourceIncrement = 2.0

  /** The default value for the max retry failed source property. */
  final val DefaultMaxRetryFailedSource = 6.hours

  /** The default value for the source check timeout property. */
  final val DefaultSourceCheckTimeout = 60.seconds

  /** The default value for the metadata check timeout property. */
  final val DefaultMetadataCheckTimeout = 30.seconds

  /** The default value for the stream cache time property. */
  final val DefaultStreamCacheTime = 4.seconds

  /** The default value for the stalled playback check property. */
  final val DefaultStalledPlaybackCheck = 5.seconds

  /**
    * Constant for a [[RadioPlayerConfig]] with default values. The contained
    * [[PlayerConfig]] is also set to default values.
    */
  final val DefaultRadioPlayerConfig: RadioPlayerConfig =
    RadioPlayerConfig(playerConfig = PlayerConfigLoader.DefaultPlayerConfig,
      maximumEvalDelay = DefaultMaxEvalDelay,
      retryFailedReplacement = DefaultRetryFailedReplacement,
      retryFailedSource = DefaultRetryFailedSource,
      retryFailedSourceIncrement = DefaultRetryFailedSourceIncrement,
      maxRetryFailedSource = DefaultMaxRetryFailedSource,
      sourceCheckTimeout = DefaultSourceCheckTimeout,
      metadataCheckTimeout = DefaultMetadataCheckTimeout,
      streamCacheTime = DefaultStreamCacheTime,
      stalledPlaybackCheck = DefaultStalledPlaybackCheck)

  /**
    * Creates a [[RadioPlayerConfig]] from the given [[Configuration]].
    *
    * @param c            the configuration
    * @param pathPrefix   the prefix for all keys
    * @param playerConfig the configuration of the audio player
    * @return the newly created [[RadioPlayerConfig]]
    */
  def loadRadioPlayerConfig(c: Configuration,
                            pathPrefix: String,
                            playerConfig: PlayerConfig): RadioPlayerConfig =
    val normalizedPrefix = if pathPrefix.endsWith(".") then pathPrefix
    else pathPrefix + "."

    def key(k: String): String = s"$normalizedPrefix$k"

    RadioPlayerConfig(playerConfig = playerConfig,
      maximumEvalDelay = c.getDuration(key(PropMaxEvalDelay), DefaultMaxEvalDelay),
      retryFailedReplacement = c.getDuration(key(PropRetryFailedReplacement), DefaultRetryFailedReplacement),
      retryFailedSource = c.getDuration(key(PropRetryFailedSource), DefaultRetryFailedSource),
      retryFailedSourceIncrement = c.getDouble(key(PropRetryFailedSourceIncrement), DefaultRetryFailedSourceIncrement),
      maxRetryFailedSource = c.getDuration(key(PropMaxRetryFailedSource), DefaultMaxRetryFailedSource),
      sourceCheckTimeout = c.getDuration(key(PropSourceCheckTimeout), DefaultSourceCheckTimeout),
      metadataCheckTimeout = c.getDuration(key(PropMetadataCheckTimeout), DefaultMetadataCheckTimeout),
      streamCacheTime = c.getDuration(key(PropStreamCacheTime), DefaultStreamCacheTime),
      stalledPlaybackCheck = c.getDuration(key(PropStalledPlaybackCheck), DefaultStalledPlaybackCheck))
