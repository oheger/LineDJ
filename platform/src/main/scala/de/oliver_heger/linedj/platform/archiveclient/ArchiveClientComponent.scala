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

import de.oliver_heger.linedj.platform.archiveclient.ArchiveClientComponent.{ArchiveServiceRegistrationData, log}
import de.oliver_heger.linedj.platform.startup.ConfigService
import de.oliver_heger.linedj.server.discovery.ServerDiscovery
import de.oliver_heger.linedj.shared.actors.ActorFactory
import org.apache.commons.configuration2.ImmutableHierarchicalConfiguration
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.ActorSystem
import org.osgi.framework.{BundleContext, ServiceRegistration}
import org.osgi.service.component.ComponentContext

import java.util.concurrent.atomic.AtomicReference
import scala.compiletime.uninitialized
import scala.concurrent.ExecutionContext

object ArchiveClientComponent:
  /** The name of the discovery instance used by this component. */
  final val ArchiveDiscoveryName = "platformArchiveDiscovery"

  /** The logger. */
  private val log = LogManager.getLogger(classOf[ArchiveClientComponent])

  /**
    * A data class holding information about the registration of the archive
    * service. This is used for cleanup when the component is deactivated.
    *
    * @param serviceRegistration the OSGi service registration
    * @param service             the archive service
    */
  private case class ArchiveServiceRegistrationData(serviceRegistration: ServiceRegistration[ArchiveService],
                                                    service: ArchiveServiceImpl)
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

    ArchiveClientConfig(config) match
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
      val archiveService = archiveServiceFactory(archiveUri, clientConfig.optContentMonitorBackoff)
        (using actorSystem, clientConfig.archiveTimeout)
      val registration = bundleContext.registerService(classOf[ArchiveService], archiveService, null)
      archiveRegistration.set(ArchiveServiceRegistrationData(registration, archiveService))
