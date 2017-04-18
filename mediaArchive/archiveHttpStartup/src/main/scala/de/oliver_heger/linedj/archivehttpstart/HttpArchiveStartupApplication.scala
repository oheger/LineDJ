/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archivehttpstart

import akka.actor.Actor.Receive
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.HttpArchiveManagementActor
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.archivehttpstart.HttpArchiveStates.HttpArchiveState
import de.oliver_heger.linedj.platform.app.support.{ActorClientSupport, ActorManagement}
import de.oliver_heger.linedj.platform.app.{ApplicationAsyncStartup, ClientApplication}
import de.oliver_heger.linedj.platform.bus.Identifiable
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaArchiveAvailabilityEvent
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension
.{ArchiveAvailabilityRegistration, ArchiveAvailabilityUnregistration}
import de.oliver_heger.linedj.shared.archive.media.ScanAllMedia
import org.osgi.service.component.ComponentContext

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object HttpArchiveStartupApplication {
  /** Property for the initialization timeout of the local archive. */
  val PropArchiveInitTimeout = "media.initTimeout"

  /** The name of the HTTP archive management actor. */
  val ManagementActorName = "httpArchiveManagementActor"

  /** The default initialization timeout (in seconds). */
  private val DefaultInitTimeout = 10
}

/**
  * An application class for stating up an HTTP archive.
  *
  * This application presents a window in which the user can enter credentials
  * for the managed HTTP archive. After login the actors of the HTTP archive
  * are created and connected to the union archive. The availability of the
  * union archive is monitored; the HTTP archive can only be started if it
  * is present.
  */
class HttpArchiveStartupApplication extends ClientApplication("httpArchiveStartup")
  with ApplicationAsyncStartup with Identifiable with ActorClientSupport
  with ActorManagement {

  import HttpArchiveStartupApplication._

  /**
    * Stores the ID for the message bus registration. Note: Both registration
    * and un-registration happen on the OSGi management thread. Therefore, it
    * is safe to store this value in a plain variable.
    */
  private var messageBusRegistrationID: Int = _

  /**
    * The current state of the HTTP archive. This variable is only changed in
    * the UI thread.
    */
  private var archiveState: HttpArchiveState = HttpArchiveStates.HttpArchiveStateNoUnionArchive

  /**
    * Stores the current availability state of the union archive. This field is
    * only changed in the UI thread.
    */
  private var unionArchiveAvailability: MediaFacade.MediaArchiveAvailabilityEvent =
    MediaFacade.MediaArchiveUnavailable

  /**
    * Stores the credentials to log into the HTTP archive if available. This
    * field is only changed in the UI thread.
    */
  private var archiveCredentials: Option[UserCredentials] = None

  /**
    * @inheritdoc This implementation adds some registrations for the message
    *             bus and archive extensions.
    */
  override def activate(compContext: ComponentContext): Unit = {
    super.activate(compContext)
    publish(ArchiveAvailabilityRegistration(componentID, archiveAvailabilityChanged))
    messageBusRegistrationID = messageBus registerListener messageBusReceive
  }

  /**
    * @inheritdoc This implementation removes registrations at the message
    *             bus.
    */
  override def deactivate(componentContext: ComponentContext): Unit = {
    messageBus removeListener messageBusRegistrationID
    publish(ArchiveAvailabilityUnregistration(componentID))
    super.deactivate(componentContext)
  }

  /**
    * Returns the message processing function for the UI bus.
    *
    * @return the message processing function
    */
  private def messageBusReceive: Receive = {
    case HttpArchiveStateRequest =>
      publish(archiveState)

    case LoginStateChanged(optCreds@Some(_)) =>
      archiveCredentials = optCreds
      triggerArchiveStartIfPossible()

    case LoginStateChanged(None) =>
      archiveCredentials = None
      if (unionArchiveAvailability == MediaFacade.MediaArchiveAvailable) {
        switchArchiveState(HttpArchiveStates.HttpArchiveStateNotLoggedIn)
      }
  }

  /**
    * The consumer function for archive availability events
    *
    * @param availabilityEvent the availability event
    */
  private def archiveAvailabilityChanged(availabilityEvent: MediaArchiveAvailabilityEvent):
  Unit = {
    if (availabilityEvent != unionArchiveAvailability) {
      unionArchiveAvailability = availabilityEvent
      availabilityEvent match {
        case MediaFacade.MediaArchiveAvailable =>
          if (!triggerArchiveStartIfPossible()) {
            switchArchiveState(HttpArchiveStates.HttpArchiveStateNotLoggedIn)
          }
        case MediaFacade.MediaArchiveUnavailable =>
          switchArchiveState(HttpArchiveStates.HttpArchiveStateNoUnionArchive)
      }
    }
  }

  /**
    * Checks whether all preconditions are fulfilled. If so, the HTTP
    * archive is started.
    *
    * @return a flag whether all preconditions are fulfilled
    */
  private def triggerArchiveStartIfPossible(): Boolean = {
    if (canStartHttpArchive && archiveNotStarted) {
      implicit val timeout = fetchInitializationTimeout()
      clientApplicationContext.mediaFacade.requestFacadeActors().onCompleteUIThread {
        case Success(actors) =>
          startupHttpArchive(actors, archiveCredentials.get)
        case Failure(ex) =>
          log.error("Could not obtain media facade actors!", ex)
          switchArchiveState(HttpArchiveStates.HttpArchiveStateNoUnionArchive)
      }
      true
    } else false
  }

  /**
    * Starts the HTTP archive using the provided object with the actors of
    * the media facade. This method is called if all preconditions have been
    * fulfilled, and the actors of the union archive could be fetched. The
    * method checks again for the preconditions (as something might have
    * changed in the meantime) and then creates the management actor.
    *
    * @param actors      the object with the actors of the union archive
    * @param credentials the current user credentials
    */
  private def startupHttpArchive(actors: MediaFacade.MediaFacadeActors, credentials:
  UserCredentials): Unit = {
    if (canStartHttpArchive) {
      val config = HttpArchiveConfig(clientApplicationContext.managementConfiguration,
        credentials)
      config match {
        case Success(c) =>
          val props = HttpArchiveManagementActor(c, actors.mediaManager,
            actors.metaDataManager)
          val actor = clientApplicationContext.actorFactory.createActor(props, ManagementActorName)
          actor ! ScanAllMedia
          registerActor(ManagementActorName, actor)
          switchArchiveState(HttpArchiveStates.HttpArchiveStateAvailable)
        case Failure(ex) =>
          log.error("Could not create HTTP archive configuration!", ex)
          switchArchiveState(HttpArchiveStates.HttpArchiveStateInvalidConfig)
      }
    }
  }

  /**
    * Checks the conditions whether the HTTP archive can be started.
    *
    * @return '''true''' if all conditions are fulfilled; '''false'''
    *         otherwise
    */
  private def canStartHttpArchive: Boolean =
    unionArchiveAvailability == MediaFacade.MediaArchiveAvailable &&
      archiveCredentials.isDefined

  /**
    * Checks if the HTTP archive has not yet been started. This is used to
    * prevent a duplicate startup.
    *
    * @return '''true''' if the HTTP archive is not started; '''false'''
    *         otherwise
    */
  private def archiveNotStarted: Boolean =
    archiveState != HttpArchiveStates.HttpArchiveStateAvailable

  /**
    * Switches to another state of the HTTP archive. If there is a change,
    * a notification message is sent. If necessary, the actors for the HTTP
    * archive are stopped.
    *
    * @param state the next state
    */
  private def switchArchiveState(state: HttpArchiveState): Unit = {
    if (archiveState != state) {
      if (archiveState.isActive && !state.isActive) {
        stopActors()
      }
      archiveState = state
      publish(state)
    }
  }

  /**
    * Fetches the initialization timeout from the management configuration. If
    * unspecified, a default value is used.
    *
    * @return the timeout
    */
  private def fetchInitializationTimeout(): Timeout =
    Timeout(clientApplicationContext.managementConfiguration.getInt(PropArchiveInitTimeout,
      DefaultInitTimeout).seconds)

  /**
    * Convenience method that allows direct access to the message bus.
    *
    * @return the UI message bus from the client application context
    */
  private def messageBus: MessageBus = clientApplicationContext.messageBus

  /**
    * Convenience method to publish a message on the UI message bus.
    *
    * @param msg the message to be published
    */
  private def publish(msg: Any): Unit = {
    messageBus publish msg
  }
}
