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
package de.oliver_heger.linedj.player.server

import akka.actor.ActorRef
import de.oliver_heger.linedj.player.engine.ActorCreator
import de.oliver_heger.linedj.player.engine.client.config.PlayerConfigLoader
import de.oliver_heger.linedj.player.engine.radio.client.config.{RadioPlayerConfigLoader, RadioSourceConfigLoader}
import de.oliver_heger.linedj.player.engine.radio.config.{MetadataConfig, RadioPlayerConfig, RadioSourceConfig}
import de.oliver_heger.linedj.player.server.PlayerServerConfig.removeLeadingSlash
import org.apache.commons.configuration.{DefaultConfigurationBuilder, HierarchicalConfiguration}

import java.nio.file.{Path, Paths}

object PlayerServerConfig:
  /**
    * Name of the configuration property for the server port. It defines the
    * port on which the HTTP server should listen.
    */
  final val PropServerPort = "serverPort"

  /**
    * Name of the configuration property defining the multicast UPD address on
    * which the server can be queried for its UI endpoint.
    */
  final val PropLookupMulticastAddress = "lookupMulticastAddress"

  /**
    * Name of the configuration property for the lookup port. A query for the
    * UI endpoint of this server has to be sent to the lookup multicast address
    * and this port.
    */
  final val PropLookupPort = "lookupPort"

  /**
    * Name of the configuration property for the lookup command. A lookup query
    * must have this string as payload; otherwise, it is ignored.
    */
  final val PropLookupCommand = "lookupCommand"

  /**
    * Name of the configuration property that defines the local folder which
    * contains the assets of the UI. This folder is served as a static
    * resource. This can be either an absolute or a relative path.
    */
  final val PropUiContentFolder = "uiContentFolder"

  /**
    * Name of the configuration property that defines the URL path for loading
    * the web UI. When requesting this path from the HTTP server, the player UI
    * is returned.
    */
  final val PropUiPath = "uiPath"

  /**
    * The name of the section containing the audio player configuration.
    */
  final val SectionPlayer = "player"

  /**
    * The name of the section containing the radio player configuration.
    */
  final val SectionRadio = s"$SectionPlayer.radio"

  /** The default port on which the server is listening. */
  final val DefaultServerPort = 8080

  /** The default multicast lookup address. */
  final val DefaultLookupMulticastAddress = "231.10.0.0"

  /** The default port for lookup queries. */
  final val DefaultLookupPort = 4321

  /** The default lookup command the server reacts on. */
  final val DefaultLookupCommand = "playerServer?"

  /** The default path with UI assets. */
  final val DefaultUiContentFolder = "ui"

  /** The default URL path of the web UI. */
  final val DefaultUiPath = "/ui/index.html"

  /** The default configuration file name. */
  final val DefaultConfigFileName = "player-server-config.xml"

  /**
    * Loads the configuration for this application from the given file. This is
    * done using [[DefaultConfigurationBuilder]]; so the file must conform to
    * the format expected by this class. Some objects that cannot be created
    * from the configuration file must be provided explicitly.
    *
    * @param configFileName    the name to the configuration file (can be a
    *                          path)
    * @param mediaManagerActor reference to the media manager actor
    * @param actorCreator      the object to create actor instances
    * @return the [[PlayerServerConfig]] constructed from this file
    */
  def apply(configFileName: String, mediaManagerActor: ActorRef, actorCreator: ActorCreator): PlayerServerConfig =
    val builder = new DefaultConfigurationBuilder(configFileName)
    val config = builder.getConfiguration().asInstanceOf[HierarchicalConfiguration]

    val playerConfig = PlayerConfigLoader.loadPlayerConfig(config, SectionPlayer, mediaManagerActor, actorCreator)
    val radioSourceConfig = RadioSourceConfigLoader.loadSourceConfig(config, SectionRadio)
    val radioMetadataConfig = RadioSourceConfigLoader.loadMetadataConfig(config, SectionRadio)
    val radioPlayerConfig = RadioPlayerConfigLoader.loadRadioPlayerConfig(config, SectionRadio, playerConfig)

    PlayerServerConfig(radioPlayerConfig = radioPlayerConfig,
      sourceConfig = radioSourceConfig,
      metadataConfig = radioMetadataConfig,
      serverPort = config.getInt(PropServerPort, DefaultServerPort),
      lookupMulticastAddress = config.getString(PropLookupMulticastAddress, DefaultLookupMulticastAddress),
      lookupPort = config.getInt(PropLookupPort, DefaultLookupPort),
      lookupCommand = config.getString(PropLookupCommand, DefaultLookupCommand),
      uiContentFolder = Paths.get(config.getString(PropUiContentFolder, DefaultUiContentFolder)),
      uiPath = config.getString(PropUiPath, DefaultUiPath))

  /**
    * Removes a leading slash from the given path if it exists. Otherwise, the
    * path is returned without changes.
    *
    * @param path the path
    * @return the path with a leading slash removed
    */
  private def removeLeadingSlash(path: String): String =
    if path.startsWith("/") then path.drop(1)
    else path
end PlayerServerConfig

/**
  * A data class holding the configuration of the player server.
  *
  * This includes both the configurations for the player engines and the
  * configuration for the server itself.
  *
  * @param radioPlayerConfig      the configuration for the radio player
  *                               engine
  * @param sourceConfig           the configuration for the radio sources
  * @param metadataConfig         the radio metadata configuration
  * @param serverPort             the port for the HTTP server
  * @param lookupMulticastAddress the address for UDP lookup requests
  * @param lookupPort             the port for lookup requests
  * @param lookupCommand          the command expected in lookup requests
  * @param uiContentFolder        the folder containing the UI assets
  * @param uiPath                 the URL path for accessing the Web UI
  */
case class PlayerServerConfig(radioPlayerConfig: RadioPlayerConfig,
                              sourceConfig: RadioSourceConfig,
                              metadataConfig: MetadataConfig,
                              serverPort: Int,
                              lookupMulticastAddress: String,
                              lookupPort: Int,
                              lookupCommand: String,
                              uiContentFolder: Path,
                              uiPath: String):
  /**
    * Returns the path prefix for requesting assets of the UI from the server.
    * This is derived from the [[uiPath]] property. The first path component is
    * considered as path prefix. (If the string starts with a slash, it is
    * removed.)
    *
    * @return the path prefix for the UI path
    */
  def uiPathPrefix: String =
    removeLeadingSlash(uiPath).takeWhile(_ != '/')
