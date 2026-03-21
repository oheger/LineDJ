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

import de.oliver_heger.linedj.shared.config.ConfigExtensions.toDuration
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder
import org.apache.commons.configuration2.builder.fluent.Parameters
import org.apache.commons.configuration2.io.{ClasspathLocationStrategy, CombinedLocationStrategy, HomeDirectoryLocationStrategy, ProvidedURLLocationStrategy}
import org.apache.commons.configuration2.{Configuration, XMLConfiguration}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

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
    * Alias for a function that can extract a concrete server configuration
    * from a passed in [[Configuration]] instance. The function returns a
    * [[Try]], since the configuration may be invalid.
    */
  type ConfigLoader[CONF] = Configuration => Try[CONF]

  /**
    * Loads the configuration for the archive server from the given
    * configuration file.
    *
    * @param configFileName the name of the configuration file to load
    * @param ec             the execution context
    * @return a [[Future]] with the configuration
    */
  def apply[CONF](configFileName: String)
                 (loader: ConfigLoader[CONF])
                 (using ec: ExecutionContext): Future[ArchiveServerConfig[CONF]] =
    loadConfiguration(configFileName) flatMap : config =>
      apply(config)(loader)

  /**
    * Extracts the configuration for the archive server from the given
    * configuration object.
    *
    * @param config the configuration to process
    * @param loader the object to load the archive configuration
    * @param ec     the execution context
    * @tparam CONF the type of the concrete archive configuration
    * @return a [[Future]] with the extracted [[ArchiveServerConfig]]
    */
  def apply[CONF](config: Configuration)
                 (loader: ConfigLoader[CONF])
                 (using ec: ExecutionContext): Future[ArchiveServerConfig[CONF]] =
    Future.fromTry(loader(config)) map : archiveConfig =>
      new ArchiveServerConfig(
        serverPort = config.getInt(PropServerPort, DefaultServerPort),
        timeout = parseTimeout(config),
        archiveConfig = archiveConfig
      )

  /**
    * Loads the server configuration from the specified configuration file.
    *
    * @param configFileName the name of the configuration file
    * @param ec             the execution context
    * @return a [[Future]] with the loaded configuration
    */
  private def loadConfiguration(configFileName: String)
                               (using ec: ExecutionContext): Future[Configuration] = Future:
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
    builder.getConfiguration

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
  * @param serverPort    the port on which the server is listening
  * @param timeout       the timeout for queries of archive content
  * @param archiveConfig the concrete configuration defining the content of the
  *                      managed archive
  * @tparam CONF the type of the archive configuration
  */
case class ArchiveServerConfig[CONF](serverPort: Int,
                                     timeout: FiniteDuration,
                                     archiveConfig: CONF)
