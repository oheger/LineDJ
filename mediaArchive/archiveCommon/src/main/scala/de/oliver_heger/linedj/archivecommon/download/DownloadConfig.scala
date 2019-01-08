/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.archivecommon.download

import java.util.concurrent.TimeUnit

import org.apache.commons.configuration.Configuration

import scala.concurrent.duration._

object DownloadConfig {
  /** Constant for the prefix for options in a configuration object. */
  private val ConfigPrefix = "media."

  /** The configuration property for the reader timeout. */
  val PropDownloadActorTimeout: String = ConfigPrefix + "downloadTimeout"

  /** The configuration property for the interval for reader timeout checks. */
  val PropDownloadCheckInterval: String = ConfigPrefix + "downloadCheckInterval"

  /** The configuration property for the download chunk size. */
  val PropDownloadChunkSize: String = ConfigPrefix + "downloadChunkSize"

  /** The default timeout for download actors. */
  val DefaultDownloadActorTimeout: FiniteDuration = 1.hour

  /** The default check interval for download operations. */
  val DefaultDownloadCheckInterval: FiniteDuration = 30.minutes

  /** The default chunk size for download operations. */
  val DefaultDownloadChunkSize = 16384

  /**
    * Creates a new instance of ''DownloadConfig'' based on the passed in
    * configuration object.
    *
    * @param config the ''Configuration''
    * @return the new ''DownloadConfig'' instance
    */
  def apply(config: Configuration): DownloadConfig =
    new DownloadConfig(
      downloadTimeout = durationProperty(config, PropDownloadActorTimeout,
        DefaultDownloadActorTimeout.toSeconds),
      downloadCheckInterval = durationProperty(config, PropDownloadCheckInterval,
        DefaultDownloadCheckInterval.toSeconds),
      downloadChunkSize = config.getInt(PropDownloadChunkSize, DefaultDownloadChunkSize))

  /**
    * Reads a property from the given configuration object and converts it to a
    * duration. This method expects that the duration is specified in seconds
    * as a long value.
    *
    * @param config   the configuration
    * @param key      the key
    * @param defValue the default value
    * @return the resulting duration
    */
  private def durationProperty(config: Configuration, key: String, defValue: Long):
  FiniteDuration =
    FiniteDuration(config.getLong(key, defValue), TimeUnit.SECONDS)
}

/**
  * A class managing the configuration options related to download operations
  * from a media archive.
  *
  * @param downloadTimeout       the timeout for download actors
  * @param downloadCheckInterval the check interval for download actors
  * @param downloadChunkSize     chunk size for download operations
  */
case class DownloadConfig private(downloadTimeout: FiniteDuration,
                                  downloadCheckInterval: FiniteDuration,
                                  downloadChunkSize: Int)
