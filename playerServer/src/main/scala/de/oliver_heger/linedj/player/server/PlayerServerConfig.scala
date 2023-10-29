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

import de.oliver_heger.linedj.player.engine.ActorCreator
import de.oliver_heger.linedj.player.engine.client.config.PlayerConfigLoader
import de.oliver_heger.linedj.player.engine.radio.RadioSource
import de.oliver_heger.linedj.player.engine.radio.client.config.{RadioPlayerConfigLoader, RadioSourceConfigLoader}
import de.oliver_heger.linedj.player.engine.radio.config.{MetadataConfig, RadioPlayerConfig, RadioSourceConfig}
import de.oliver_heger.linedj.player.server.PlayerServerConfig.{PropCurrentSource, Slash, removeLeadingSlash}
import org.apache.commons.configuration.{CombinedConfiguration, Configuration, DefaultConfigurationBuilder, FileConfiguration}
import org.apache.pekko.actor.ActorRef

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
    * Name of the configuration property that defines the shutdown command.
    * Here an optional command can be specified that is executed when the
    * server terminates. This can be used for instance to do some special
    * clean-up or shutdown the current device.
    */
  final val PropShutdownCommand = "shutdownCommand"

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
    * The name of a configuration that stores the name of the currently
    * selected radio. If there is such a sub configuration in the server
    * configuration, the current source is loaded from there, and it is also
    * updated if the user selects another source. If this configuration is a
    * file-based configuration, its ''auto-save'' flag is enabled, so that
    * changes on the current source are persisted automatically.
    */
  final val CurrentSourceConfigName = "currentConfig"

  /**
    * The name of the configuration property that stores the name of the
    * currently selected radio source.
    */
  final val PropCurrentSource = "radio.current"

  /** Constant for a path separator. */
  private val Slash = '/'

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
    val config = builder.getConfiguration(true)

    apply(config, mediaManagerActor, actorCreator)

  /**
    * Parses the configuration for this application from the given
    * [[CombinedConfiguration]] object. This variant expects that a combined
    * configuration has already been loaded. It is processed now to construct a
    * [[PlayerServerConfig]]. Some objects that cannot be created from the
    * configuration file must be provided explicitly.
    *
    * @param config            the combined configuration for the player server
    * @param mediaManagerActor reference to the media manager actor
    * @param actorCreator      the object to create actor instances
    * @return the [[PlayerServerConfig]] constructed from this configuration
    */
  def apply(config: CombinedConfiguration,
            mediaManagerActor: ActorRef,
            actorCreator: ActorCreator): PlayerServerConfig = {
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
      uiPath = config.getString(PropUiPath, DefaultUiPath),
      optCurrentConfig = getAndInitCurrentConfig(config),
      optShutdownCommand = Option(config.getString(PropShutdownCommand)))
  }

  /**
    * Returns the sub configuration that stores the current radio source from
    * the given combined configuration if it is available. This configuration
    * is needed directly, since it is updated when the current source is
    * changed. If the configuration is a file-based configuration, its auto-save
    * flag is set, so that changes on the current source are automatically
    * persisted.
    *
    * @param config the combined configuration for the player server
    * @return an ''Option'' with the configuration for the editable values
    */
  private def getAndInitCurrentConfig(config: CombinedConfiguration): Option[Configuration] =
    val optCurrentConfig = Option(config.getConfiguration(CurrentSourceConfigName))
    optCurrentConfig foreach {
      case fc: FileConfiguration =>
        fc.setAutoSave(true)
      case _ =>
    }
    optCurrentConfig

  /**
    * Removes a leading slash from the given path if it exists. Otherwise, the
    * path is returned without changes.
    *
    * @param path the path
    * @return the path with a leading slash removed
    */
  private def removeLeadingSlash(path: String): String =
    if path.startsWith(Slash.toString) then path.drop(1)
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
  * @param optCurrentConfig       the optional configuration to store the
  *                               current radio source
  * @param optShutdownCommand     an optional command to be executed when the
  *                               server shuts down
  */
case class PlayerServerConfig(radioPlayerConfig: RadioPlayerConfig,
                              sourceConfig: RadioSourceConfig,
                              metadataConfig: MetadataConfig,
                              serverPort: Int,
                              lookupMulticastAddress: String,
                              lookupPort: Int,
                              lookupCommand: String,
                              uiContentFolder: Path,
                              uiPath: String,
                              optCurrentConfig: Option[Configuration],
                              optShutdownCommand: Option[String]):
  /**
    * Returns the path prefix for requesting assets of the UI from the server.
    * This is derived from the [[uiPath]] property. The first path component is
    * considered as path prefix. (If the string starts with a slash, it is
    * removed.)
    *
    * @return the path prefix for the UI path
    */
  def uiPathPrefix: String =
    val normalizedPrefix = removeLeadingSlash(uiPath)
    if !normalizedPrefix.contains(Slash) then ""
    else normalizedPrefix.takeWhile(_ != '/')

  /**
    * Returns the name of the currently selected radio source or ''None'' if
    * this information is not available. In order for the source to be
    * available, [[optCurrentConfig]] must be defined, and the corresponding
    * property must be set.
    *
    * @return an ''Option'' with the name of the current radio source
    */
  def currentSourceName: Option[String] =
    optCurrentConfig flatMap { c => Option(c.getString(PropCurrentSource)) }

  /**
    * Returns the currently selected radio source or ''None'' if this
    * information is not available. This function tries to resolve the name of
    * the current radio source obtained from the current configuration against
    * the radio sources in [[sourceConfig]]. So, result is ''None'' if the name
    * cannot be resolved.
    *
    * @return an ''Option'' with the current radio source
    */
  def currentSource: Option[RadioSource] =
    currentSourceName flatMap { name =>
      sourceConfig.namedSources.find(_._1 == name).map(_._2)
    }

  /**
    * Returns a radio source that should be played when the application is
    * started. If a valid current radio source is defined in the configuration,
    * this source is returned. Otherwise, the first radio source from the list
    * of sources is returned. Result is ''None'' if there no radio source at
    * all.
    *
    * @return an ''Option'' with the radio source to be played initially
    */
  def initialSource: Option[RadioSource] = currentSource orElse sourceConfig.sources.headOption
  