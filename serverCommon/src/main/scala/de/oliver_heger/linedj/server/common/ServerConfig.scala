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

package de.oliver_heger.linedj.server.common

import org.apache.commons.configuration2.{BaseHierarchicalConfiguration, ImmutableHierarchicalConfiguration}

import scala.util.Try

/**
  * A data class storing the configuration properties for the discovery
  * mechanism. If such a configuration is defined, a [[ServerLocator]] is
  * instantiated which allows a dynamic discovery of the address the server is
  * listening on.
  *
  * @param multicastAddress the multicast request for discovery requests
  * @param port             the port for discovery requests
  * @param command          the command for discovery requests
  */
final case class DiscoveryConfig(multicastAddress: String,
                                 port: Int,
                                 command: String)

object ServerConfig:
  /**
    * The name of the section in the configuration file that contains the
    * properties for setting up the server.
    */
  final val SectionServer = "server"

  /**
    * Name of the configuration property for the server port. It defines the
    * port on which the HTTP server should listen.
    */
  final val PropServerPort = "httpPort"

  /**
    * The name of the section in the configuration file that is related to the
    * discovery mechanism. If all mandatory properties in this section are
    * defined, the server supports discovery based on multicast UDP requests.
    */
  final val SectionDiscovery = "discovery"

  /**
    * Name of the configuration property defining the multicast UPD address on
    * which the server can be queried for its UI endpoint.
    */
  final val PropDiscoveryMulticastAddress = "multicastAddress"

  /** Name of the configuration property for the discovery port. */
  final val PropDiscoveryPort = "port"

  /**
    * Name of the configuration property for the command for the discovery
    * mechanism. A discovery request must have this string as payload;
    * otherwise, it is ignored.
    */
  final val PropDiscoveryCommand = "command"

  /** The mandatory properties for the discovery configuration. */
  private val DiscoveryProperties = List(PropDiscoveryMulticastAddress, PropDiscoveryPort, PropDiscoveryCommand)

  /**
    * Tries to create a [[ServerConfig]] from parsing the specified 
    * configuration structure. Most properties are considered mandatory, unless
    * default values are provided to this function. For the configuration of 
    * the discovery mechanism, either all mandatory properties or no properties
    * at all must be present (in the latter case, discovery is disabled). In
    * case of an invalid configuration, result is a ''Failure''.
    *
    * @param config         the configuration to parse
    * @param optDefaultPort an optional default binding port
    * @return a [[Try]] with the extracted [[ServerConfig]]
    */
  def apply(config: ImmutableHierarchicalConfiguration, optDefaultPort: Option[Int] = None): Try[ServerConfig] = Try:
    val serverConfig = subConfig(config, SectionServer)
    val serverPort = parseServerPort(serverConfig, optDefaultPort)
    ServerConfig(
      httpPort = serverPort,
      optDiscoveryConfig = parseDiscoveryConfig(serverConfig)
    )

  /**
    * Returns the port the server should listen on. The port must be specified
    * in the configuration, unless a default port is provided. The function 
    * throws an exception if no port can be determined.
    *
    * @param config         the configuration with server properties
    * @param optDefaultPort an optional default port
    * @return the port the server should bind to
    */
  private def parseServerPort(config: ImmutableHierarchicalConfiguration, optDefaultPort: Option[Int]): Int =
    optDefaultPort match
      case Some(port) =>
        config.getInt(PropServerPort, port)
      case None =>
        checkMandatoryProperty(config, PropServerPort)
        config.getInt(PropServerPort)

  /**
    * Parses the discovery configuration form the given server configuration. 
    * Throws an exception if mandatory properties are missing.
    *
    * @param serverConfig the configuration for the server
    * @return an [[Option]] with the extracted [[DiscoveryConfig]]
    */
  private def parseDiscoveryConfig(serverConfig: ImmutableHierarchicalConfiguration): Option[DiscoveryConfig] =
    val discoveryConfig = subConfig(serverConfig, SectionDiscovery)
    if discoveryConfig.isEmpty then
      None
    else
      DiscoveryProperties.foreach(p => checkMandatoryProperty(discoveryConfig, p))
      Some(
        DiscoveryConfig(
          multicastAddress = discoveryConfig.getString(PropDiscoveryMulticastAddress),
          port = discoveryConfig.getInt(PropDiscoveryPort),
          command = discoveryConfig.getString(PropDiscoveryCommand)
        )
      )

  /**
    * Checks whether the configuration contains a mandatory key and throws an
    * exception if this is not the case.
    *
    * @param config the configuration
    * @param key    the required key
    */
  private def checkMandatoryProperty(config: ImmutableHierarchicalConfiguration, key: String): Unit =
    if !config.containsKey(key) then
      throw new IllegalArgumentException(s"Missing mandatory configuration key: '$key'.")

  /**
    * Returns a sub configuration of the given configuration at the given key.
    * (So that the sub configuration contains only keys starting with this
    * prefix.) If there are no such keys, an empty configuration is returned.
    *
    * @param config the configuration
    * @param prefix the key prefix to select
    * @return the sub configuration with keys starting with this prefix
    */
  private def subConfig(config: ImmutableHierarchicalConfiguration,
                        prefix: String): ImmutableHierarchicalConfiguration =
    Try(config.immutableConfigurationAt(prefix)).getOrElse(new BaseHierarchicalConfiguration)
end ServerConfig

/**
  * A data class to represent the part of the configuration evaluated by the
  * server framework. This is mainly related to how to start the server and
  * configure the HTTP listener and if and how a discovery mechanism should be
  * supported.
  *
  * @param httpPort           the HTTP port to listen on
  * @param optDiscoveryConfig an optional configuration for the discovery
  *                           mechanism; if present, a [[ServerLocator]] is
  *                           instantiated
  */
final case class ServerConfig(httpPort: Int,
                              optDiscoveryConfig: Option[DiscoveryConfig])
