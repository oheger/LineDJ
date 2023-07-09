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

import de.oliver_heger.linedj.player.engine.radio.client.config.RadioSourceConfigLoader
import de.oliver_heger.linedj.player.engine.radio.config.{MetadataConfig, RadioSourceConfig}
import net.sf.jguiraffe.gui.app.ApplicationContext
import org.apache.commons.configuration.HierarchicalConfiguration

object RadioPlayerClientConfig {
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

  /**
    * Creates a [[RadioPlayerClientConfig]] instance from the passed in configuration
    * object.
    *
    * @param config the configuration
    * @return the [[RadioPlayerClientConfig]] instance
    */
  def apply(config: HierarchicalConfiguration): RadioPlayerClientConfig = {
    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    val metaConfig = RadioSourceConfigLoader.loadMetadataConfig(config)

    new RadioPlayerClientConfig(sourceConfig = sourceConfig,
      metadataConfig = metaConfig,
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
}

/**
  * A data class storing the full configuration of the radio player
  * application.
  *
  * @param sourceConfig    the configuration of radio sources
  * @param metadataConfig  the metadata configuration
  * @param initialDelay    the delay before starting playback
  * @param metaMaxLen      the maximum length of metadata before rotation of
  *                        the text is needed
  * @param metaRotateSpeed the scale factor for rotation of metadata
  */
case class RadioPlayerClientConfig(sourceConfig: RadioSourceConfig,
                                   metadataConfig: MetadataConfig,
                                   initialDelay: Int,
                                   metaMaxLen: Int,
                                   metaRotateSpeed: Double)
