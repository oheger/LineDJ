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

package de.oliver_heger.linedj.archive.server

import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import org.apache.commons.configuration.{Configuration, XMLConfiguration}

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}

object ArchiveServerConfig:
  /** The default name of the configuration file loaded by this server. */
  final val DefaultConfigFileName = "archive-server-config.xml"

  /**
    * The name of the section in the configuration for the properties of the
    * server itself.
    */
  final val ServerSection = "server."

  /**
    * The name of the configuration property defining the port on which the
    * HTTP server should listen.
    */
  final val PropServerPort = ServerSection + "port"

  /** The default port for the HTTP server. */
  final val DefaultServerPort = 8080

  /**
    * Loads the configuration for the archive server from the given
    * configuration file.
    *
    * @param configFileName the name of the configuration file to load
    * @param ec             the execution context
    * @return a [[Future]] with the configuration
    */
  def apply(configFileName: String)(using ec: ExecutionContext): Future[ArchiveServerConfig] = Future:
    apply(new XMLConfiguration(configFileName))

  /**
    * Extracts the configuration for the archive server from the given
    * configuration object.
    *
    * @param config the configuration to process
    * @return the extracted [[ArchiveServerConfig]]
    */
  def apply(config: Configuration): ArchiveServerConfig =
    new ArchiveServerConfig(
      serverPort = config.getInt(PropServerPort, DefaultServerPort),
      archiveConfigs = MediaArchiveConfig(config)
    )
end ArchiveServerConfig

/**
  * A data class for holding the configuration settings of the archive server.
  *
  * The class defines the settings of the server itself, but also the archives
  * to be loaded and served.
  *
  * @param serverPort     the port on which the server is listening
  * @param archiveConfigs a collection with the configurations of the archives
  *                       to make available via the server's REST API
  */
case class ArchiveServerConfig(serverPort: Int,
                               archiveConfigs: Seq[MediaArchiveConfig])
