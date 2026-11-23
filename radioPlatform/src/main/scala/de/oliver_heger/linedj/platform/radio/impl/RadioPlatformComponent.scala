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

package de.oliver_heger.linedj.platform.radio.impl

import de.oliver_heger.linedj.platform.app.{ClientContextSupport, PlatformComponent}
import de.oliver_heger.linedj.platform.startup.ConfigService
import de.oliver_heger.linedj.server.discovery.ServerDiscovery
import de.oliver_heger.linedj.shared.actors.ActorFactory
import de.oliver_heger.linedj.shared.config.ConfigExtensions.toDuration
import org.apache.commons.configuration2.ImmutableHierarchicalConfiguration
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.Timeout
import org.osgi.service.component.ComponentContext

import scala.compiletime.uninitialized
import scala.concurrent.duration.*
import scala.util.Try

object RadioPlatformComponent:
  /** The logger. */
  private val log = LogManager.getLogger(classOf[RadioPlatformComponent])

  /** The prefix of all configuration keys related to the radio platform. */
  private val ConfigSection = "platform.radio"

  /** The default timeout for requests to the player server. */
  final val DefaultRequestTimeout: FiniteDuration = 30.seconds

  /**
    * Configuration property for the timeout when sending requests to the
    * player server. The value is a number with an optional unit.
    */
  private val PropRequestTimeout = "requestTimeout"

  /**
    * The name of the section under the configuration tree for the radio
    * platform that contains the properties for the server discovery.
    */
  private val DiscoverySection = "discovery"

  /**
    * Configuration property defining the multicast address to be used for
    * server discovery.
    */
  private val PropDiscoveryAddress = "multicastAddress"

  /**
    * Configuration property defining the port to be used for server discovery.
    */
  private val PropDiscoveryPort = "port"

  /**
    * Configuration property defining the request code to send to the player
    * server during discovery.
    */
  private val PropDiscoveryCode = "requestCode"

  /**
    * Configuration property defining the timeout for a single request sent
    * during the discovery operation. If no response from the server is
    * received within this timespan, another request is sent after the
    * configured delay.
    */
  private val PropDiscoveryTimeout = "timeout"

  /**
    * Configuration property for the minimum backoff of the discovery
    * operation.
    */
  private val PropDiscoveryMinBackoff = "minBackoff"

  /**
    * Configuration property for the maximum backoff of the discovery
    * operation.
    */
  private val PropDiscoveryMaxBackoff = "maxBackoff"

  /**
    * Extracts the parameters for the server discovery from the given
    * configuration. Result is None if the configuration is incomplete.
    *
    * @param config the current platform configuration
    * @return an Option with the discovery parameters
    */
  private def discoveryParams(config: ImmutableHierarchicalConfiguration):
  Option[ServerDiscovery.DiscoveryParams] =
    subSection(config, ConfigSection).flatMap: section =>
      for
        discovery <- subSection(section, DiscoverySection)
        address <- Option(discovery.getString(PropDiscoveryAddress))
        port <- extractPort(discovery)
        code <- Option(discovery.getString(PropDiscoveryCode))
      yield ServerDiscovery.DiscoveryParams(
        multicastAddress = address,
        port = port,
        requestCode = code,
        timeout = extractDuration(discovery, PropDiscoveryTimeout, ServerDiscovery.DefaultTimeout),
        minBackoff = extractDuration(discovery, PropDiscoveryMinBackoff, ServerDiscovery.DefaultMinBackoff),
        maxBackoff = extractDuration(discovery, PropDiscoveryMaxBackoff, ServerDiscovery.DefaultMaxBackoff)
      )

  /**
    * Extracts the timeout for requests to the player server from the given
    * configuration. If it is not defined, the default timeout is returned.
    *
    * @param config the current platform configuration
    * @return the timeout for requests to the player server
    */
  private def requestTimeout(config: ImmutableHierarchicalConfiguration): FiniteDuration =
    subSection(config, ConfigSection)
      .map(section => extractDuration(section, PropRequestTimeout, DefaultRequestTimeout))
      .getOrElse(DefaultRequestTimeout)

  /**
    * Returns the subsection of the given configuration with the specified key.
    * Result is None if there is no such subsection.
    *
    * @param config the configuration to evaluate
    * @param key    the key of the desired subsection
    * @return an Option with the subsection
    */
  private def subSection(config: ImmutableHierarchicalConfiguration, key: String):
  Option[ImmutableHierarchicalConfiguration] =
    Try(config.immutableConfigurationAt(key)).toOption

  /**
    * Extracts the discovery port from the given configuration.
    *
    * @param config the configuration containing the discovery properties
    * @return an Option with the discovery port
    */
  private def extractPort(config: ImmutableHierarchicalConfiguration): Option[Int] =
    if config.containsKey(PropDiscoveryPort) then
      Some(config.getInt(PropDiscoveryPort))
    else
      None

  /**
    * Extracts a duration property from the given configuration. The default is
    * used if the property is missing or cannot be parsed.
    *
    * @param config  the configuration to evaluate
    * @param key     the key of the duration property
    * @param default the default duration
    * @return the configured or default duration
    */
  private def extractDuration(config: ImmutableHierarchicalConfiguration,
                              key: String,
                              default: FiniteDuration): FiniteDuration =
    Option(config.getString(key)).flatMap(_.toDuration.toOption).getOrElse(default)
end RadioPlatformComponent

/**
  * A declarative services component to manage the radio platform module.
  *
  * This component inspects the platform configuration whether it defines
  * parameters to discover a player server instance. If this is the case, it
  * creates a [[RadioControllerActor]] and sets up the radio platform.
  *
  * @param controllerFactory the factory for the controller actor
  */
class RadioPlatformComponent(controllerFactory: RadioControllerActor.Factory)
  extends PlatformComponent, ClientContextSupport:
  def this() = this(RadioControllerActor.newInstance)

  import RadioPlatformComponent.*

  /**
    * The config service. Note: This property is accessed only on the OSGi
    * management thread.
    */
  private var configService: ConfigService = uninitialized

  /**
    * The controller actor created during activation, if the configuration
    * contains a complete discovery configuration. Note: This property is
    * accessed only on the OSGi management thread. Therefore, no 
    * synchronization is needed.
    */
  private var optControllerActor: Option[ActorRef[RadioControllerActor.Command]] = None

  /**
    * Initializes the dependency to the [[ConfigService]]. This method is
    * called by the declarative services runtime.
    *
    * @param configService the [[ConfigService]]
    */
  def initConfigService(configService: ConfigService): Unit =
    log.info("ConfigService is set.")
    this.configService = configService

  /**
    * Activates this component. If the configuration contains a complete
    * discovery configuration, a controller actor is created.
    *
    * @param compContext the component context
    */
  override def activate(compContext: ComponentContext): Unit =
    super.activate(compContext)
    log.info("Initializing RadioPlatformComponent.")

    val config = configService.config
    discoveryParams(config).foreach: params =>
      log.info("Starting a discovery operation with these parameters: {}.", params)

      given ActorFactory = clientApplicationContext.actorFactory

      given Timeout = Timeout(requestTimeout(config))

      optControllerActor = Some(controllerFactory(params, clientApplicationContext.messageBus))

  /**
    * Deactivates this component. A controller actor created during activation
    * is stopped.
    *
    * @param componentContext the component context
    */
  override def deactivate(componentContext: ComponentContext): Unit =
    super.deactivate(componentContext)
    log.info("Deactivating RadioPlatformComponent.")

    optControllerActor.foreach: actor =>
      actor ! RadioControllerActor.Command.Stop
    optControllerActor = None
