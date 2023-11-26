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

package de.oliver_heger.linedj.archivecommon.download

import java.util.concurrent.TimeUnit

import org.apache.commons.configuration.Configuration

import scala.concurrent.duration._

object DownloadConfig:
  /** The configuration property for the reader timeout. */
  final val PropDownloadActorTimeout: String = "downloadTimeout"

  /** The configuration property for the interval for reader timeout checks. */
  final val PropDownloadCheckInterval: String = "downloadCheckInterval"

  /** The configuration property for the download chunk size. */
  final val PropDownloadChunkSize: String = "downloadChunkSize"

  /** The default timeout for download actors. */
  final val DefaultDownloadActorTimeout: FiniteDuration = 1.hour

  /** The default check interval for download operations. */
  final val DefaultDownloadCheckInterval: FiniteDuration = 30.minutes

  /** The default chunk size for download operations. */
  final val DefaultDownloadChunkSize = 16384

  /**
    * Constant for a ''DownloadConfig'' instance that is initialized only with
    * default values.
    */
  final val DefaultDownloadConfig: DownloadConfig = new DownloadConfig(DefaultDownloadActorTimeout,
    DefaultDownloadCheckInterval,
    DefaultDownloadChunkSize)

  /**
    * Creates a new instance of ''DownloadConfig'' based on the passed in
    * configuration object. It is expected that the properties supported by
    * this class are on the top-level of the passed in configuration.
    *
    * @param config    the ''Configuration''
    * @param defConfig an object with default values for missing properties
    * @return the new ''DownloadConfig'' instance
    */
  def apply(config: Configuration, defConfig: DownloadConfig = DefaultDownloadConfig): DownloadConfig =
    new DownloadConfig(
      downloadTimeout = durationProperty(config, PropDownloadActorTimeout,
        defConfig.downloadTimeout.toSeconds),
      downloadCheckInterval = durationProperty(config, PropDownloadCheckInterval,
        defConfig.downloadCheckInterval.toSeconds),
      downloadChunkSize = config.getInt(PropDownloadChunkSize, defConfig.downloadChunkSize))

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

/**
  * A class managing the configuration options related to download operations
  * from a media archive.
  *
  * @param downloadTimeout       the timeout for download actors
  * @param downloadCheckInterval the check interval for download actors
  * @param downloadChunkSize     chunk size for download operations
  */
case class DownloadConfig(downloadTimeout: FiniteDuration,
                          downloadCheckInterval: FiniteDuration,
                          downloadChunkSize: Int)
