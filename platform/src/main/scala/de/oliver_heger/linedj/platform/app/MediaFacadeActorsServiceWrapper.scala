/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.platform.app

import de.oliver_heger.linedj.platform.bus.Identifiable
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension.{ArchiveAvailabilityRegistration, ArchiveAvailabilityUnregistration}
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.Actor.Receive
import org.apache.pekko.util.Timeout
import org.osgi.framework.{BundleContext, ServiceRegistration}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

object MediaFacadeActorsServiceWrapper:
  /** Property for the initialization timeout of the local archive. */
  val PropArchiveInitTimeout = "media.initTimeout"

  /** The default initialization timeout (in seconds). */
  private val DefaultInitTimeout = 10

  /**
    * An internally used message class to report the availability of facade
    * actors via the message bus. This is used to sync the service registration
    * with the UI thread where the internal state is managed.
    *
    * @param facadeActors the facade actors
    */
  private case class FacadeActorsAvailable(facadeActors: MediaFacadeActors)

  /**
    * Determines the initialization timeout for the local media archive. This
    * value can be read from the configuration; if unspecified, a default value
    * is used.
    *
    * @param ctx the ''ClientApplicationContext''
    * @return the initialization timeout
    */
  private def fetchInitTimeout(ctx: ClientApplicationContext): Timeout =
    Timeout(ctx.managementConfiguration.getInt(PropArchiveInitTimeout,
      DefaultInitTimeout).seconds)

/**
  * An internally used helper class that exposes the actors of the media facade
  * as OSGi service.
  *
  * For complex interaction with the media facade access to the implementing
  * actors is frequently required. Obtaining these actors is not trivial,
  * however, as they have to retrieved in an asynchronous operation that might
  * fail; also, the availability of the media archive has to be monitored
  * because only if the archive is running, the facade actors are valid.
  *
  * To make the life easier for consumers of these actors, this class centrally
  * implements all steps required to request the facade actors and monitor
  * their validity. When the actors have been retrieved successfully, they are
  * registered as a service (of type [[MediaFacadeActors]] in the OSGi registry.
  *
  * Consumers can use standard OSGi mechanisms to obtain the actors directly
  * without having to do the full dance.
  *
  * @param clientAppContext the client application content
  * @param bundleContext    the OSGi bundle context
  */
private class MediaFacadeActorsServiceWrapper(val clientAppContext: ClientApplicationContext,
                                              val bundleContext: BundleContext)
  extends Identifiable:

  import MediaFacadeActorsServiceWrapper._

  /** The logger. */
  private val log = LogManager.getLogger(getClass)

  /** The registration ID for the message bus listener. */
  private var msgBusListenerID = 0

  /**
    * Holds the registration for the facades service. Note that here an atomic
    * reference must be used because the field accessed from both the UI
    * thread and the OSGi management thread.
    */
  private val refRegFacadeActors: AtomicReference[Option[ServiceRegistration[MediaFacadeActors]]] =
    new AtomicReference(None)

  /** A flag whether the union archive is currently available. */
  private var unionArchiveAvailable = false

  /**
    * Notifies this object that it is activated. Performs the necessary
    * initialization.
    */
  def activate(): Unit =
    log.info("Activating MediaFacadeActorsServiceWrapper.")
    msgBusListenerID = clientAppContext.messageBus registerListener messageBusReceive
    clientAppContext.messageBus publish ArchiveAvailabilityRegistration(componentID,
      archiveAvailabilityChanged)

  /**
    * Notifies this object that it has been deactivated. Performs the
    * necessary cleanup.
    */
  def deactivate(): Unit =
    clientAppContext.messageBus removeListener msgBusListenerID
    clientAppContext.messageBus publish ArchiveAvailabilityUnregistration(componentID)
    ensureServiceRegistrationCleared()
    log.info("Deactivated MediaFacadeActorsServiceWrapper.")

  /**
    * Returns the function for handling messages published on the message bus.
    *
    * @return the message handling function
    */
  private def messageBusReceive: Receive =
    case FacadeActorsAvailable(actors) =>
      registerFacadeService(actors)

  /**
    * The consumer function for the archive availability extension. Here
    * messages about the availability of the union media archive are processed.
    *
    * @param event the event
    */
  private def archiveAvailabilityChanged(event: MediaFacade.MediaArchiveAvailabilityEvent): Unit =
    event match
      case MediaFacade.MediaArchiveAvailable =>
        handleArchiveAvailable()
      case MediaFacade.MediaArchiveUnavailable =>
        handleArchiveUnavailable()

  /**
    * Handles the notification that the union archive is available. If not
    * yet done, the local archive is now started.
    */
  private def handleArchiveAvailable(): Unit =
    unionArchiveAvailable = true
    if refRegFacadeActors.get().isEmpty then
      implicit val executionContext: ExecutionContextExecutor =
        clientAppContext.actorSystem.dispatcher
      implicit val timeout: Timeout = fetchInitTimeout(clientAppContext)

      log.info("Archive available. Requesting facade actors.")
      clientAppContext.mediaFacade.requestFacadeActors() foreach { actors =>
        clientAppContext.messageBus publish FacadeActorsAvailable(actors)
      }

  /**
    * Handles the notification that the union archive is no longer available.
    * This also causes the local archive to be stopped.
    */
  private def handleArchiveUnavailable(): Unit =
    unionArchiveAvailable = false
    log.info("Archive unavailable.")
    ensureServiceRegistrationCleared()

  /**
    * Registers the facade actors as OSGi services if the union archive is
    * still available.
    *
    * @param facadeActors the facade actors
    */
  private def registerFacadeService(facadeActors: MediaFacadeActors): Unit =
    if unionArchiveAvailable then
      refRegFacadeActors.set(Some(bundleContext.registerService(classOf[MediaFacadeActors],
        facadeActors, null)))
      log.info("MediaFacadeActors service registered.")

  /**
    * Removes the service registration if available.
    */
  private def ensureServiceRegistrationCleared(): Unit =
    refRegFacadeActors.get() foreach { reg =>
      reg.unregister()
      refRegFacadeActors.set(None)
    }
