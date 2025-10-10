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
import de.oliver_heger.linedj.archive.server.MediaArchiveConfigLoaderCC2.given
import de.oliver_heger.linedj.shared.config.ConfigExtensions
import de.oliver_heger.linedj.shared.config.ConfigExtensions.toDuration
import org.apache.commons.configuration2.{Configuration, XMLConfiguration}
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder
import org.apache.commons.configuration2.builder.fluent.Parameters
import org.apache.commons.configuration2.io.{ClasspathLocationStrategy, CombinedLocationStrategy, HomeDirectoryLocationStrategy, ProvidedURLLocationStrategy}

import scala.collection.immutable.Seq
import scala.concurrent.duration.{DurationInt, FiniteDuration}
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

  /**
    * The name of the configuration property that defines the timeout for
    * interactions with actors. The archive server uses actors to hold the
    * content of the archive. The value that is specified here is set as global
    * ''ask'' timeout. The property value can be a string with a temporal unit.
    * If no unit is specified, the unit ''seconds'' is assumed.
    */
  final val PropServerTimeout = ServerSection + "timeout"

  /** The default port for the HTTP server. */
  final val DefaultServerPort = 8080

  /** The default timeout when querying actors. */
  final val DefaultServerTimeout = 3.seconds

  /**
    * Loads the configuration for the archive server from the given
    * configuration file.
    *
    * @param configFileName the name of the configuration file to load
    * @param ec             the execution context
    * @return a [[Future]] with the configuration
    */
  def apply(configFileName: String)(using ec: ExecutionContext): Future[ArchiveServerConfig] = Future:
    import scala.jdk.CollectionConverters.*
    val params = new Parameters
    val locationStrategies = List(
      new ProvidedURLLocationStrategy,
      new ClasspathLocationStrategy,
      new HomeDirectoryLocationStrategy
    )
    val builder = new FileBasedConfigurationBuilder(classOf[XMLConfiguration])
      .configure(
        params.xml()
          .setFileName(configFileName)
          .setLocationStrategy(new CombinedLocationStrategy(locationStrategies.asJava))
      )

    apply(builder.getConfiguration)

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
      timeout = parseTimeout(config),
      archiveConfigs = MediaArchiveConfig.loadMediaArchiveConfigs(config)
    )

  /**
    * Obtains the value of the [[PropServerTimeout]] property from the given
    * configuration.
    *
    * @param config the configuration to process
    * @return the value of the ''timeout'' property
    */
  private def parseTimeout(config: Configuration): FiniteDuration =
    if config.containsKey(PropServerTimeout) then
      config.getString(PropServerTimeout).toDuration.get
    else
      DefaultServerTimeout
end ArchiveServerConfig

/**
  * A data class for holding the configuration settings of the archive server.
  *
  * The class defines the settings of the server itself, but also the archives
  * to be loaded and served.
  *
  * @param serverPort     the port on which the server is listening
  * @param timeout        the timeout for queries of archive content
  * @param archiveConfigs a collection with the configurations of the archives
  *                       to make available via the server's REST API
  */
case class ArchiveServerConfig(serverPort: Int,
                               timeout: FiniteDuration,
                               archiveConfigs: Seq[MediaArchiveConfig])
