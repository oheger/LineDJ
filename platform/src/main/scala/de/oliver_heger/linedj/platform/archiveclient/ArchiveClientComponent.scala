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

package de.oliver_heger.linedj.platform.archiveclient

import com.github.cloudfiles.core.http.factory.HttpRequestSenderFactoryImpl
import de.oliver_heger.linedj.platform.archiveclient.ArchiveClientComponent.{ArchiveServiceRegistrationData, log}
import de.oliver_heger.linedj.platform.startup.ConfigService
import de.oliver_heger.linedj.server.discovery.ServerDiscovery
import de.oliver_heger.linedj.shared.actors.ActorFactory
import de.oliver_heger.linedj.shared.config.ConfigExtensions.toDuration
import org.apache.commons.configuration2.ImmutableHierarchicalConfiguration
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.ActorSystem
import org.osgi.framework.{BundleContext, ServiceRegistration}
import org.osgi.service.component.ComponentContext

import java.util.concurrent.atomic.AtomicReference
import scala.compiletime.uninitialized
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Try

object ArchiveClientComponent:
  /** The name of the discovery instance used by this component. */
  final val ArchiveDiscoveryName = "platformArchiveDiscovery"

  /**
    * The key of the subsection in the platform configuration under which the
    * properties for the media archive are located.
    */
  final val ArchiveConfigSection = "platform.mediaArchive"

  /**
    * The key of the subsection in the archive configuration under which the
    * properties for the discovery are located.
    */
  final val DiscoveryConfigSection = "discovery"

  /**
    * The name of the mandatory configuration property for the multicast
    * address to use for the archive discovery operation.
    */
  final val PropDiscoveryAddress = "multicastAddress"

  /**
    * The name of the mandatory configuration property for the port to use for
    * the archive discovery operation.
    */
  final val PropDiscoveryPort = "port"

  /**
    * The name of the mandatory configuration property for the request code for
    * the archive discovery operation.
    */
  final val PropDiscoveryCode = "requestCode"

  /**
    * The name of the optional configuration property defining the timeout for
    * a single request during the archive discovery operation.
    */
  final val PropDiscoveryTimeout = "timeout"

  /**
    * The name of the optional configuration property defining the minimum
    * backoff when retrying requests during the archive discovery operation.
    */
  final val PropDiscoveryMinBackoff = "minBackoff"

  /**
    * The name of the optional configuration property defining the maximum
    * backoff when retrying requests during the archive discovery operation.
    */
  final val PropDiscoveryMaxBackoff = "maxBackoff"

  /**
    * The name of the optional configuration property defining a timeout for
    * requests to the archive server.
    */
  final val PropArchiveTimeout = "requestTimeout"

  /**
    * The default timeout for sending requests to the archive server. This is
    * used if no explicit timeout is set in the client configuration.
    */
  final val DefaultArchiveTimeout = 30.seconds

  /** The logger. */
  private val log = LogManager.getLogger(classOf[ArchiveClientComponent])

  /**
    * An internally used data class to store all the parameters required by
    * this component. The properties reflect the part of the platform
    * configuration consumed by this class.
    *
    * @param discoveryParams the parameters for the discovery operation
    * @param archiveTimeout  the timeout for requests to the archive
    */
  private case class ArchiveClientConfig(discoveryParams: ServerDiscovery.DiscoveryParams,
                                         archiveTimeout: FiniteDuration)

  /**
    * A data class holding information about the registration of the archive
    * service. This is used for cleanup when the component is deactivated.
    *
    * @param serviceRegistration the OSGi service registration
    * @param service             the archive service
    */
  private case class ArchiveServiceRegistrationData(serviceRegistration: ServiceRegistration[ArchiveService],
                                                    service: ArchiveServiceImpl)

  /**
    * Tries to create an [[ArchiveClientConfig]] from the properties of the
    * current platform configuration. If mandatory properties are undefined,
    * result is _None_.
    *
    * @param config the platform configuration
    * @return an [[Option]] with the configuration for this component
    */
  private def extractClientConfig(config: ImmutableHierarchicalConfiguration): Option[ArchiveClientConfig] =
    for
      clientConfig <- subSection(config, ArchiveConfigSection)
      discoveryConfig <- subSection(clientConfig, DiscoveryConfigSection)
      address <- Option(discoveryConfig.getString(PropDiscoveryAddress))
      port <- extractDiscoveryPort(discoveryConfig)
      code <- Option(discoveryConfig.getString(PropDiscoveryCode))
    yield
      ArchiveClientConfig(
        discoveryParams = ServerDiscovery.DiscoveryParams(
          multicastAddress = address,
          port = port,
          requestCode = code,
          timeout = extractDurationProperty(discoveryConfig, PropDiscoveryTimeout, ServerDiscovery.DefaultTimeout),
          minBackoff = extractDurationProperty(
            discoveryConfig,
            PropDiscoveryMinBackoff,
            ServerDiscovery.DefaultMinBackoff
          ),
          maxBackoff = extractDurationProperty(
            discoveryConfig,
            PropDiscoveryMaxBackoff,
            ServerDiscovery.DefaultMaxBackoff
          )
        ),
        archiveTimeout = extractDurationProperty(clientConfig, PropArchiveTimeout, DefaultArchiveTimeout)
      )

  /**
    * Tries to get a subsection from the given configuration under a specific
    * key.
    *
    * @param config the configuration
    * @param key    the key
    * @return an [[Option]] with the subconfiguration at this key
    */
  private def subSection(config: ImmutableHierarchicalConfiguration, key: String):
  Option[ImmutableHierarchicalConfiguration] = Try(config.immutableConfigurationAt(key)).toOption

  /**
    * Extracts the port parameter for the discovery from the given
    * configuration. Result is _None_ if the parameter is missing.
    *
    * @param config the configuration
    * @return an [[Option]] with the port for the discovery
    */
  private def extractDiscoveryPort(config: ImmutableHierarchicalConfiguration): Option[Int] =
    if config.containsKey(PropDiscoveryPort) then
      Some(config.getInt(PropDiscoveryPort))
    else
      None

  /**
    * Extracts an optional duration property from the given configuration.
    *
    * @param config  the configuration
    * @param key     the key of the property
    * @param default the default value for this property
    * @return the extracted property value
    */
  private def extractDurationProperty(config: ImmutableHierarchicalConfiguration,
                                      key: String,
                                      default: FiniteDuration): FiniteDuration =
    Option(config.getString(key)).flatMap(_.toDuration.toOption).getOrElse(default)
end ArchiveClientComponent

/**
  * A class setting up the infrastructure to access a media archive managed by
  * an HTTP server.
  *
  * This class is instantiated and managed by the declarative services runtime.
  * When all its dependencies are satisfied, it starts a discovery operation to
  * find an archive server in the local network (provided that the
  * corresponding configuration options are defined in the platform
  * configuration). On successful completion of the discovery operation, the
  * class registers services at the OSGi registry to interact with the archive.
  *
  * @param discoveryFactory      the factory to start a discovery operation
  * @param archiveServiceFactory the factory to create archive service
  */
class ArchiveClientComponent(discoveryFactory: ServerDiscovery.Factory,
                             archiveServiceFactory: ArchiveServiceImpl.Factory):
  /** The actor system of the platform. */
  private var actorSystem: ActorSystem = uninitialized

  /** The platform configuration. */
  private var config: ImmutableHierarchicalConfiguration = uninitialized

  /** The handle for the discovery operation. */
  private var optDiscoveryHandle: Option[ServerDiscovery.DiscoveryHandle] = None

  /**
    * Stores data about a registration of the archive service. Based on this
    * data, cleanup can be performed when the component is deactivated. Note
    * that this data needs to be accessible from multiple threads; therefore,
    * it is hold by an atomic reference.
    */
  private val archiveRegistration = new AtomicReference[ArchiveServiceRegistrationData]

  /**
    * The default constructor needed by OSGi.
    */
  def this() = this(ServerDiscovery.discover, ArchiveServiceImpl.newInstance)

  import ArchiveClientComponent.*

  /**
    * Activates this component. This method is called by the SCR.
    *
    * @param compContext the component context
    */
  def activate(compContext: ComponentContext): Unit =
    log.info("Activating {}.", getClass.getSimpleName)

    extractClientConfig(config) match
      case Some(clientConfig) =>
        log.info("Starting discovery operation for media archive.")
        log.debug("Discovery parameter: {}.", clientConfig.discoveryParams)

        given ActorSystem = actorSystem

        val discoveryHandle = discoveryFactory.apply(clientConfig.discoveryParams, ArchiveDiscoveryName)
        handleDiscoveryResult(discoveryHandle, clientConfig, compContext.getBundleContext)
        optDiscoveryHandle = Some(discoveryHandle)
      case None =>
        log.info("No discovery operation is started since the configuration is missing or incomplete.")

  /**
    * Deactivates this component. This method is called by the SCR when this
    * component is shutdown.
    *
    * @param componentContext the component context
    */
  def deactivate(componentContext: ComponentContext): Unit =
    log.info("Deactivating {}.", getClass.getSimpleName)
    optDiscoveryHandle.foreach(_.close())
    Option(archiveRegistration.get()) foreach : registration =>
      log.info("Unregistering archive service.")
      registration.serviceRegistration.unregister()
      registration.service.close()

  /**
    * Initializes the actor system of this component. This function is called
    * by the SCR.
    *
    * @param system the actor system
    */
  def initActorSystem(system: ActorSystem): Unit =
    actorSystem = system

  /**
    * Initializes the configuration service. This function is called by the
    * SCR.
    *
    * @param configService the configuration service
    */
  def initConfigService(configService: ConfigService): Unit =
    config = configService.config

  /**
    * Handles the result of the discovery operation. When the URI of the
    * archive server has been discovered, this function creates an
    * [[ArchiveService]] and registers it at the OSGi runtime.
    *
    * @param discoveryHandle the discovery handle
    * @param clientConfig    the archive client configuration
    * @param bundleContext   the bundle context
    */
  private def handleDiscoveryResult(discoveryHandle: ServerDiscovery.DiscoveryHandle,
                                    clientConfig: ArchiveClientConfig,
                                    bundleContext: BundleContext): Unit =
    given ExecutionContext = actorSystem.dispatcher

    discoveryHandle.futResult foreach : archiveUri =>
      log.info("Discovered archive server at '{}'.", archiveUri)
      val archiveService = archiveServiceFactory(archiveUri,
        HttpRequestSenderFactoryImpl)(using actorSystem, clientConfig.archiveTimeout)
      val registration = bundleContext.registerService(classOf[ArchiveService], archiveService, null)
      archiveRegistration.set(ArchiveServiceRegistrationData(registration, archiveService))
