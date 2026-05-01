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

package de.oliver_heger.linedj.archive.server

import de.oliver_heger.linedj.server.common.ConfigSupport
import de.oliver_heger.linedj.shared.config.ConfigExtensions.toDuration
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder
import org.apache.commons.configuration2.builder.fluent.Parameters
import org.apache.commons.configuration2.io.{ClasspathLocationStrategy, CombinedLocationStrategy, HomeDirectoryLocationStrategy, ProvidedURLLocationStrategy}
import org.apache.commons.configuration2.{Configuration, ImmutableHierarchicalConfiguration, XMLConfiguration}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object ArchiveServerConfig:
  /**
    * The name of the section in the configuration for the properties of the
    * archive content management consumed by this class.
    */
  final val ArchiveSection = "media.archive."

  /**
    * The name of the configuration property that defines the timeout for
    * interactions with actors. The archive server uses actors to hold the
    * content of the archive. The value that is specified here is set as global
    * ''ask'' timeout. The property value can be a string with a temporal unit.
    * If no unit is specified, the unit ''seconds'' is assumed.
    */
  final val PropActorTimeout = ArchiveSection + "actorTimeout"

  /** The default timeout when querying actors. */
  final val DefaultActorTimeout = 3.seconds
  
  /**
    * Extracts the configuration for the archive server from the given
    * configuration object.
    *
    * @param config the configuration to process
    * @param loader the object to load the archive configuration
    * @tparam CONF the type of the concrete archive configuration
    * @return a [[Try]] with the extracted [[ArchiveServerConfig]]
    */
  def apply[CONF](config: ImmutableHierarchicalConfiguration)
                 (loader: ConfigSupport.ConfigLoader[CONF]): Try[ArchiveServerConfig[CONF]] =
    loader(config) map : archiveConfig =>
      new ArchiveServerConfig(
        actorTimeout = parseTimeout(config),
        archiveConfig = archiveConfig
      )
  
  /**
    * Obtains the value of the [[PropActorTimeout]] property from the given
    * configuration.
    *
    * @param config the configuration to process
    * @return the value of the ''timeout'' property
    */
  private def parseTimeout(config: ImmutableHierarchicalConfiguration): FiniteDuration =
    if config.containsKey(PropActorTimeout) then
      config.getString(PropActorTimeout).toDuration.get
    else
      DefaultActorTimeout
end ArchiveServerConfig

/**
  * A data class for holding the configuration settings of the archive server.
  *
  * The class defines the settings related to the management of the archive
  * content, but also stores the archive-specific configuration which is 
  * managed on behalf of a concrete server implementation.
  * 
  * For now, the configuration for the archive content is minimalistic. This
  * might change in the future if more functionality is added.
  *
  * @param actorTimeout       the timeout for queries of archive content
  * @param archiveConfig the concrete configuration of the managed archive
  * @tparam CONF the type of the archive configuration
  */
case class ArchiveServerConfig[CONF](actorTimeout: FiniteDuration,
                                     archiveConfig: CONF)
