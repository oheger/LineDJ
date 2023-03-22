/*
 * Copyright 2015-2023 The Developers Team.
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

import de.oliver_heger.linedj.player.engine.radio.config.RadioSourceConfig
import net.sf.jguiraffe.gui.app.ApplicationContext
import org.apache.commons.configuration.{Configuration, HierarchicalConfiguration}

import scala.annotation.tailrec
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * A data class defining the configuration options used for handling errors.
  *
  * The strategy for handling errors depends on multiple properties:
  *  - ''retryInterval'': When an error occurs the strategy will pause at least
  *    for this interval and then retry. For multiple errors in series, the
  *    interval can be increased with the ''retryIncrementFactor''.
  *  - ''retryIncrementFactor'': A factor for incrementing the retry interval
  *    if there are multiple errors from the same radio source. This typically
  *    indicates a more permanent problem with this source. Therefore, it does
  *    not make sense to retry it directly, but make the pauses between two
  *    attempts longer and longer, until a configurable maximum is reached.
  *    This factor must be greater than 1.
  *  - ''maxRetries'': Defines the maximum number of retries for a failing
  *    radio source before the player switches to another source. Note that
  *    this setting also determines the maximum interval between two retries.
  *  - ''recoveryTime'': The time of successful playback after an error to
  *    reset the error state.
  *  - ''recoveryMinFailedSources'': The minimum number of sources that must be
  *    marked as dysfunctional before a recovery from an error is attempted.
  *    Switching back to the original source makes only sense if there was a
  *    general network problem which is now fixed. Then we expect that multiple
  *    sources have been marked as dysfunctional. If there are only a few
  *    sources affected, this may indicate a (more permanent) problem with
  *    these sources, e.g. an incorrect URL. In this case, switching back to
  *    such a source is likely to fail again; and we should not interrupt
  *    playback every time the recovery interval is reached.
  *
  * @param retryInterval           the retry interval
  * @param retryIncrementFactor    the factor to increment the retry interval
  *                                if the failure persists
  * @param maxRetries              the maximum number of retries
  * @param recoveryTime            time after which to recover from error state
  * @param recoverMinFailedSources number of sources to recover from error
  *                                state
  */
case class ErrorHandlingConfig(retryInterval: FiniteDuration,
                               retryIncrementFactor: Double,
                               maxRetries: Int,
                               recoveryTime: Long,
                               recoverMinFailedSources: Int) {
  /**
    * The maximum retry interval based on the minimum interval and the
    * maximum number of retries.
    */
  lazy val maxRetryInterval: Long = calcMaxRetry(maxRetries, retryInterval.toMillis)

  /**
    * Calculates the maximum retry interval based on the allowed number of
    * retries.
    *
    * @param count the current counter
    * @return the maximum retry interval
    */
  @tailrec private def calcMaxRetry(count: Int, value: Long): Long =
    if (count == 0) value
    else calcMaxRetry(count - 1, math.round(retryIncrementFactor * value))
}

object RadioPlayerClientConfig {
  /**
    * The default value for the ''retryInterval'' configuration property (in
    * milliseconds).
    */
  final val DefaultRetryInterval = 1000

  /** Minimum value for the retry interval (in milliseconds). */
  final val MinimumRetryInterval = 10

  /**
    * The default value for the ''retryIncrement'' configuration property. This
    * value causes a pretty fast increment of retry intervals.
    */
  final val DefaultRetryIncrement = 2.0

  /** Minimum retry increment factor. */
  final val MinimumRetryIncrement = 1.1

  /**
    * The default value for the ''maxRetries'' configuration property.
    */
  final val DefaultMaxRetries = 5

  /**
    * Constant for an initial delay before starting playback (in milliseconds).
    *
    * It might be necessary to wait for a while until the audio player engine
    * is set up, and all playback context factories have been registered. The
    * time to wait can be specified in the configuration. If this is not done,
    * this default value is used.
    */
  final val DefaultInitialDelay = 5000

  /**
    * The default maximum text length of metadata that can be displayed in the
    * UI.
    */
  final val DefaultMetadataMaxLen = 50

  /**
    * The default scale factor for metadata rotation. This is applied when the
    * metadata text exceeds the configured maximum length.
    */
  final val DefaultMetadataRotateScale = 1.0

  /** Default time interval for error recovery (in seconds). */
  final val DefaultRecoveryTime = 600L

  /** Default minimum number of dysfunctional sources before recovery. */
  final val DefaultMinFailuresForRecovery = 1

  /**
    * The common prefix for all configuration keys.
    */
  private val KeyPrefix = "radio."

  /** Configuration key for the initial delay. */
  private val KeyInitialDelay = KeyPrefix + "initialDelay"

  /**
    * Configuration key for the maximum length of metadata that can be
    * displayed directly in the UI. If the metadata exceeds this length, it is
    * rotated.
    */
  private val KeyMetaMaxLen = KeyPrefix + "metadataMaxLen"

  /**
    * Configuration key for the scale factor when rotating the metadata text.
    * This factor determines the speed of the rotation. It is a double value
    * that is interpreted as the number of scroll steps per second. For
    * instance, a value of 4.0 means that every 250 milliseconds the text is
    * scrolled by one character. Note that 10.0 is maximum, since only 10
    * playback progress events arrive per second.
    */
  private val KeyMetaRotateSpeed = KeyPrefix + "metadataRotateSpeed"

  /** The common prefix for keys related to error handling. */
  private val ErrorKeyPrefix = KeyPrefix + "error."

  /** Configuration key for the (minimum) retry interval. */
  private val KeyInterval = ErrorKeyPrefix + "retryInterval"

  /** Configuration key for the retry interval increment factor. */
  private val KeyIncrement = ErrorKeyPrefix + "retryIncrement"

  /** Configuration key for the maximum number of retries for a failing source. */
  private val KeyMaxRetries = ErrorKeyPrefix + "maxRetries"

  /** Configuration key prefix for error recovery keys. */
  private val RecoveryKeyPrefix = ErrorKeyPrefix + "recovery."

  /** Configuration key for the error recovery time. */
  private val KeyRecoveryTime = RecoveryKeyPrefix + "time"

  /** Configuration key for the minimum failed sources before recovery. */
  private val KeyRecoveryMinFailures = RecoveryKeyPrefix + "minFailedSources"

  /**
    * Creates a [[RadioPlayerClientConfig]] instance from the passed in configuration
    * object.
    *
    * @param config the configuration
    * @return the [[RadioPlayerClientConfig]] instance
    */
  def apply(config: HierarchicalConfiguration): RadioPlayerClientConfig = {
    val sourceConfig = RadioSourceConfigLoader.load(config)
    val errorConfig = readErrorConfig(config)

    new RadioPlayerClientConfig(sourceConfig = sourceConfig,
      errorConfig = errorConfig,
      initialDelay = config.getInt(KeyInitialDelay, DefaultInitialDelay),
      metaMaxLen = config.getInt(KeyMetaMaxLen, DefaultMetadataMaxLen),
      metaRotateSpeed = config.getDouble(KeyMetaRotateSpeed, DefaultMetadataRotateScale))
  }

  /**
    * Creates a [[RadioPlayerClientConfig]] instance from the configuration
    * associated with the given [[ApplicationContext]].
    *
    * @param context the application context
    * @return the [[RadioPlayerClientConfig]] instance
    */
  def apply(context: ApplicationContext): RadioPlayerClientConfig =
    apply(context.getConfiguration.asInstanceOf[HierarchicalConfiguration])

  /**
    * Creates an [[ErrorHandlingConfig]] instance from the given configuration.
    *
    * @param config the configuration
    * @return the configuration related to error handling
    */
  private def readErrorConfig(config: Configuration): ErrorHandlingConfig = {
    val interval = math.max(MinimumRetryInterval,
      config.getInt(KeyInterval, DefaultRetryInterval)).millis
    val increment = math.max(MinimumRetryIncrement,
      config.getDouble(KeyIncrement, DefaultRetryIncrement))
    val maxRetryCount = config.getInt(KeyMaxRetries, DefaultMaxRetries)
    val recoveryTime = config.getLong(KeyRecoveryTime, DefaultRecoveryTime)
    val recoveryMinFailures = config.getInt(KeyRecoveryMinFailures, DefaultMinFailuresForRecovery)
    ErrorHandlingConfig(interval, increment, maxRetryCount, recoveryTime, recoveryMinFailures)
  }
}

/**
  * A data class storing the full configuration of the radio player
  * application.
  *
  * @param sourceConfig    the configuration of radio sources
  * @param errorConfig     the error handling configuration
  * @param initialDelay    the delay before starting playback
  * @param metaMaxLen      the maximum length of metadata before rotation of
  *                        the text is needed
  * @param metaRotateSpeed the scale factor for rotation of metadata
  */
case class RadioPlayerClientConfig(sourceConfig: RadioSourceConfig,
                                   errorConfig: ErrorHandlingConfig,
                                   initialDelay: Int,
                                   metaMaxLen: Int,
                                   metaRotateSpeed: Double)
