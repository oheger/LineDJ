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

package de.oliver_heger.linedj.player.engine.radio.config

import de.oliver_heger.linedj.player.engine.PlayerConfig

import scala.concurrent.duration._

/**
  * A class collecting configuration options for a radio player.
  *
  * A radio player is based on an audio player; therefore, this configuration
  * embeds a corresponding [[PlayerConfig]]. In addition, it defines a number
  * of specific configuration settings.
  *
  * Note that this class does not contain a [[RadioSourceConfig]] object. This
  * is because the configuration of radio sources may be changed dynamically.
  * The settings defined here remain constant over the life-time of a radio
  * player.
  *
  * @param playerConfig               the configuration for the audio player
  * @param maximumEvalDelay           the maximum delay after which a new
  *                                   evaluation of the current radio source
  *                                   takes place; even if no restrictions were
  *                                   found
  * @param retryFailedReplacement     an interval after which another attempt
  *                                   to find a replacement source is made if
  *                                   no such source could be found
  * @param retryFailedSource          the initial delay until to retry a source
  *                                   whose playback failed
  * @param retryFailedSourceIncrement a factor to increment the delay until the
  *                                   next check for a failed source
  * @param maxRetryFailedSource       the maximum delay until to retry a source
  *                                   whose playback failed
  * @param sourceCheckTimeout         a timeout for retrying a failed source;
  *                                   if the check lasts longer than this
  *                                   value, the source is considered still in
  *                                   error state
  */
case class RadioPlayerConfig(playerConfig: PlayerConfig,
                             maximumEvalDelay: FiniteDuration = 1.hour,
                             retryFailedReplacement: FiniteDuration = 1.minute,
                             retryFailedSource: FiniteDuration = 5.seconds,
                             retryFailedSourceIncrement: Double = 2.0,
                             maxRetryFailedSource: FiniteDuration = 6.hours,
                             sourceCheckTimeout: FiniteDuration = 60.seconds)
