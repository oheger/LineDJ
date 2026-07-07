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

import de.oliver_heger.linedj.platform.archiveclient.ArchiveClientComponent.{ServiceRegistrationData, log}
import de.oliver_heger.linedj.platform.startup.ConfigService
import de.oliver_heger.linedj.server.discovery.ServerDiscovery
import de.oliver_heger.linedj.shared.actors.ActorFactory
import org.apache.commons.configuration2.ImmutableHierarchicalConfiguration
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.util.Timeout
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
    * A data class holding information about the registration of a  service
    * managed by this component. This is used for cleanup when the component is
    * deactivated.
    *
    * @param serviceRegistration the OSGi service registration
    * @param service             the reference to the service
    * @tparam T the concrete type of the service
    * @tparam R the base type of the registration
    */
  private case class ServiceRegistrationData[T <: AutoCloseable, R >: T](serviceRegistration: ServiceRegistration[R],
                                                                         service: T)

  /**
    * Performs an unregistration of the service in the given reference if it is
    * defined.
    *
    * @param ref the reference
    * @tparam T the type of the service
    * @tparam R the type of the registration
    */
  private def unregister[T <: AutoCloseable, R >: T](ref: AtomicReference[ServiceRegistrationData[T, R]]): Unit =
    Option(ref.get()) foreach : registration =>
      log.info("Unregistering service {}.", registration.service.getClass.getSimpleName)
      registration.serviceRegistration.unregister()
      registration.service.close()
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
  * @param archiveServiceFactory the factory to create the archive service
  * @param loginServiceFactory   the factory to create the login service
  */
class ArchiveClientComponent(discoveryFactory: ServerDiscovery.Factory,
                             archiveServiceFactory: ArchiveServiceImpl.Factory,
                             loginServiceFactory: LoginServiceImpl.Factory):
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
  private val archiveServiceRegistration =
    new AtomicReference[ServiceRegistrationData[ArchiveServiceImpl, ArchiveService]]

  /**
    * Stores data about a registration of the login service. This is analogous
    * to the registration data of the archive service.
    */
  private val loginServiceRegistration =
    new AtomicReference[ServiceRegistrationData[LoginServiceImpl, LoginService]]

  /**
    * The default constructor needed by OSGi.
    */
  def this() = this(ServerDiscovery.discover, ArchiveServiceImpl.newInstance, LoginServiceImpl.newInstance)

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
    unregister(loginServiceRegistration)
    unregister(archiveServiceRegistration)

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
      archiveServiceRegistration.set(ServiceRegistrationData(registration, archiveService))

      createAndRegisterLoginService(archiveService, clientConfig, bundleContext)

  /**
    * Checks whether the archive server supports a login state for cloud
    * archives. If so, the function creates and registers a [[LoginService]] in
    * the OSGi registry.
    *
    * @param archiveService the archive service
    * @param clientConfig   the archive client configuration
    * @param bundleContext  the bundle context
    * @param ec             the execution context
    */
  private def createAndRegisterLoginService(archiveService: ArchiveService,
                                            clientConfig: ArchiveClientConfig,
                                            bundleContext: BundleContext)
                                           (using ec: ExecutionContext): Unit =
    LoginServiceImpl.queryArchiveState(archiveService) foreach : _ =>
      log.info("Archive server supports login information. Registering a LoginService.")

      given ActorSystem = actorSystem

      given Timeout = clientConfig.archiveTimeout

      val loginService = loginServiceFactory(
        archiveService,
        clientConfig.optArchiveStatusMonitorBackoff
      )
      val registration = bundleContext.registerService(classOf[LoginService], loginService, null)
      loginServiceRegistration.set(ServiceRegistrationData(registration, loginService))
