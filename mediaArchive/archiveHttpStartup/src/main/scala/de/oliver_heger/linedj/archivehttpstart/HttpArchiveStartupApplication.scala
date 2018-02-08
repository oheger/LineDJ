/*
 * Copyright 2015-2018 The Developers Team.
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

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.Actor.Receive
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.archivehttp.{HttpArchiveStateConnected, HttpArchiveStateResponse}
import de.oliver_heger.linedj.archivehttpstart.HttpArchiveStates._
import de.oliver_heger.linedj.platform.app.support.{ActorClientSupport, ActorManagement}
import de.oliver_heger.linedj.platform.app.{ApplicationAsyncStartup, ClientApplication}
import de.oliver_heger.linedj.platform.bus.Identifiable
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaArchiveAvailabilityEvent
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension.{ArchiveAvailabilityRegistration, ArchiveAvailabilityUnregistration}
import net.sf.jguiraffe.gui.app.ApplicationContext
import org.osgi.service.component.ComponentContext

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object HttpArchiveStartupApplication {
  /** Property for the initialization timeout of the local archive. */
  val PropArchiveInitTimeout = "media.initTimeout"

  /**
    * Property for the state request timeout for an HTTP archive. After an
    * archive has been started, it is sent a request to query its current
    * state. This request may time out and is then repeated. With this
    * property a timeout (in seconds) is specified.
    */
  val PropArchiveStateRequestTimeout = "media.stateRequestTimeout"

  /**
    * The name of the bean with the configuration manager for the managed HTTP
    * archives. This bean is created on application startup and stored in the
    * central bean context.
    */
  val BeanConfigManager = "httpArchiveConfigManager"

  /** The default initialization timeout (in seconds). */
  private val DefaultInitTimeout = 10

  /** The default state request timeout (in seconds). */
  private val DefaultStateRequestTimeout = 60

  /**
    * A set with states indicating that an HTTP archive has not yet started.
    * This is used to determine which archives can be started if there is a
    * change in preconditions.
    */
  private val StatesNotStarted: Set[HttpArchiveState] = Set(HttpArchiveStateNotLoggedIn,
    HttpArchiveStateNoUnionArchive)

  /**
    * An internally used data class that stores state information about a
    * single managed HTTP archive.
    *
    * @param state        the current state of this archive
    * @param actors       a map with the actors created for this archive
    * @param archiveIndex the index used for the archive when it was started
    */
  private case class ArchiveStateData(state: HttpArchiveStateChanged,
                                      actors: Map[String, ActorRef],
                                      archiveIndex: Int) {
    /**
      * Tries to resolve the current management actor for the represented
      * archive. If the archive has not yet been created, result is ''None''.
      *
      * @param shortName the short name of the archive in question
      * @return an ''Option'' with the resolved management actor
      */
    def managerActor(shortName: String): Option[ActorRef] = {
      val mgrName = HttpArchiveStarter.archiveActorName(shortName,
        HttpArchiveStarter.ManagementActorName, archiveIndex)
      actors get mgrName
    }
  }

  /**
    * Maps an archive state returned by the management actor of an archive to
    * a state used by the startup application.
    *
    * @param arcName the name of the archive
    * @param state   the state of this archive
    * @return the mapped state
    */
  private def mapArchiveStartedState(arcName: String,
                                     state: de.oliver_heger.linedj.archivehttp.HttpArchiveState):
  HttpArchiveStateChanged = {
    val mappedState = state match {
      case HttpArchiveStateConnected =>
        HttpArchiveStateAvailable
      case s =>
        HttpArchiveErrorState(s)
    }
    HttpArchiveStateChanged(arcName, mappedState)
  }
}

/**
  * An application class for stating up an HTTP archive.
  *
  * The application manages an arbitrary number of realms and HTTP archives
  * assigned to these realms. It presents information about the status of the
  * archives and the realms. The user can enter credentials for realms, then
  * the associated archives are started.
  *
  * The availability of the union archive is monitored; HTTP archives can
  * only be started if it is present.
  *
  * @param archiveStarter the object to start the HTTP archive
  */
class HttpArchiveStartupApplication(val archiveStarter: HttpArchiveStarter)
  extends ClientApplication("httpArchiveStartup")
    with ApplicationAsyncStartup with Identifiable with ActorClientSupport
    with ActorManagement {

  /**
    * Creates a new instance of ''HttpArchiveStartupApplication'' with default
    * settings.
    *
    * @return the new instance
    */
  def this() = this(new HttpArchiveStarter)

  import HttpArchiveStartupApplication._

  /**
    * Stores the ID for the message bus registration. Note: The registration
    * is done during application context creation in a background thread.
    * Therefore, is has to be ensured that the value is published in a safe
    * way.
    */
  private val messageBusRegistrationID = new AtomicInteger

  /**
    * Stores the current availability state of the union archive. This field is
    * only changed in the UI thread.
    */
  private var unionArchiveAvailability: MediaFacade.MediaArchiveAvailabilityEvent =
    MediaFacade.MediaArchiveUnavailable

  /** Stores login information about the managed realms. */
  private var realms = Map.empty[String, UserCredentials]

  /**
    * A map with state information about the archives managed by this
    * application. Keys are archive names.
    */
  private var archiveStates = Map.empty[String, ArchiveStateData]

  /** The object managing configuration data about HTTP archives. */
  private var configManager: HttpArchiveConfigManager = _

  /** A counter for generating unique indices when creating archive actors. */
  private var archiveIndexCounter = 0

  /**
    * @inheritdoc This implementation adds some registrations for the message
    *             bus and archive extensions.
    */
  override def activate(compContext: ComponentContext): Unit = {
    super.activate(compContext)
  }

  /**
    * @inheritdoc This implementation removes registrations at the message
    *             bus.
    */
  override def deactivate(componentContext: ComponentContext): Unit = {
    removeRegistrations()
    super.deactivate(componentContext)
  }

  /**
    * @inheritdoc This implementation reads information from the application's
    *             configuration about the HTTP archives to be managed.
    */
  override def createApplicationContext(): ApplicationContext = {
    val context = super.createApplicationContext()
    configManager = HttpArchiveConfigManager(clientApplicationContext.managementConfiguration)
    archiveStates = createInitialArchiveState()
    addBeanDuringApplicationStartup(BeanConfigManager, configManager)
    addRegistrations()
    context
  }

  /**
    * Creates the initial state for all managed archives that no union archive
    * is available. In this case, no HTTP archives can be active.
    *
    * @return the map with the initial states for all archives
    */
  private def createInitialArchiveState(): Map[String, ArchiveStateData] =
    configManager.archives.keySet.foldLeft(
      Map.empty[String, ArchiveStateData]) { (m, n) =>
      val state = HttpArchiveStateChanged(n, HttpArchiveStateNoUnionArchive)
      m + (n -> ArchiveStateData(state, Map.empty, 0))
    }

  /**
    * Adds the required registrations for message bus listeners and consumers.
    */
  private def addRegistrations(): Unit = {
    publish(ArchiveAvailabilityRegistration(componentID, archiveAvailabilityChanged))
    messageBusRegistrationID.set(messageBus registerListener messageBusReceive)
  }

  /**
    * Removes the registrations for message bus listeners and consumers. This
    * method is invoked when the application is deactivated.
    */
  private def removeRegistrations(): Unit = {
    messageBus removeListener messageBusRegistrationID.get()
    publish(ArchiveAvailabilityUnregistration(componentID))
  }

  /**
    * Returns the message processing function for the UI bus.
    *
    * @return the message processing function
    */
  private def messageBusReceive: Receive = {
    case HttpArchiveStateRequest =>
      publishArchiveStates()

    case LoginStateChanged(realm, Some(creds)) =>
      removeRealmCredentials(realm)
      realms += realm -> creds
      triggerArchiveStartIfPossible()

    case LoginStateChanged(realm, None) =>
      removeRealmCredentials(realm)

    case not@HttpArchiveStateChanged(_, HttpArchiveStateInitializing) =>
      queryAndPublishArchiveState(not)
  }

  /**
    * Determines the current state of the archive referred to by the given
    * change message. When the answer arrives it is propagated to listeners on
    * the message bus.
    *
    * @param stateChange the state change notification
    */
  private def queryAndPublishArchiveState(stateChange: HttpArchiveStateChanged): Unit = {
    val archiveName = configManager.archives(stateChange.archiveName).shortName
    val optMgrActor = archiveStates(stateChange.archiveName).managerActor(archiveName)
    optMgrActor foreach { mgrActor =>
      val timeoutSecs = clientApplicationContext.managementConfiguration.getInt(
        PropArchiveStateRequestTimeout, DefaultStateRequestTimeout)
      implicit val timeout: Timeout = Timeout(timeoutSecs.seconds)
      val futState = mgrActor ? de.oliver_heger.linedj.archivehttp.HttpArchiveStateRequest
      futState.mapTo[HttpArchiveStateResponse]
        .map(r => mapArchiveStartedState(r.archiveName, r.state))
        .onComplete {
          case Success(st) => publish(st)
          case Failure(ex) =>
            log.warn("Timeout when querying archive state.", ex)
            publish(stateChange)
        }
    }
  }

  /**
    * Publishes the current states of all managed HTTP archives.
    */
  private def publishArchiveStates(): Unit = {
    configManager.archives.keySet foreach { n =>
      publish(archiveStates(n).state)
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
          onUnionArchiveAvailable()

        case MediaFacade.MediaArchiveUnavailable =>
          onUnionArchiveUnavailable()
      }
    }
  }

  /**
    * Handles the notification that the union archive is now available. This
    * changes the state for all archives for which no login credentials are
    * available. The other ones can now be started.
    */
  private def onUnionArchiveAvailable(): Unit = {
    switchToNotLoggedInState()
    triggerArchiveStartIfPossible()
  }

  /**
    * Handles a notification that the union archive is no longer available.
    * This causes the state of all HTTP archives to be reset, and all actors
    * created by this application are stopped.
    */
  private def onUnionArchiveUnavailable(): Unit = {
    stopActors()
    archiveStates = createInitialArchiveState()
    publishArchiveStates()
  }

  /**
    * Changes the state of archives for which no user credentials are available
    * after the union archive becomes available.
    */
  private def switchToNotLoggedInState(): Unit = {
    configManager.archives filterNot { e =>
      realms.contains(e._2.realm)
    } foreach { e =>
      val stateData = ArchiveStateData(HttpArchiveStateChanged(e._1,
        HttpArchiveStateNotLoggedIn), Map.empty, 0)
      archiveStates += (e._1 -> stateData)
      publish(stateData.state)
    }
  }

  /**
    * Checks whether the union archive is available as precondition for the
    * start of HTTP archives. If this is the case, all archives for which
    * login credentials are available are now started.
    */
  private def triggerArchiveStartIfPossible(): Unit = {
    if (canStartHttpArchives && archivesToBeStarted.nonEmpty) {
      triggerArchiveStart()
    }
  }

  /**
    * Triggers the start of all HTTP archives for which login information is
    * available.
    */
  private def triggerArchiveStart(): Unit = {
    implicit val timeout: Timeout = fetchInitializationTimeout()
    clientApplicationContext.mediaFacade.requestFacadeActors().onCompleteUIThread {
      case Success(actors) =>
        startupHttpArchives(actors)
      case Failure(ex) =>
        log.error("Could not obtain media facade actors!", ex)
        onUnionArchiveUnavailable()
    }
  }

  /**
    * Starts all HTTP archives with defined credentials using the provided
    * object with the actors of the media facade. This method is called if
    * the union archive is available, login credentials have been added,
    * and the actors of the union archive could be fetched. The method checks
    * again for the preconditions (as something might have changed in the
    * meantime) and then calls the archive starter for each archive that can be
    * started.
    *
    * @param mediaActors the object with the actors of the union archive
    */
  private def startupHttpArchives(mediaActors: MediaFacade.MediaFacadeActors): Unit = {
    if (canStartHttpArchives) {
      archivesToBeStarted foreach { e =>
        archiveIndexCounter += 1
        val actors = archiveStarter.startup(mediaActors, e._2,
          clientApplicationContext.managementConfiguration, realms(e._2.realm),
          clientApplicationContext.actorFactory, archiveIndexCounter)
        actors foreach (e => registerActor(e._1, e._2))
        val arcState = ArchiveStateData(actors = actors,
          state = HttpArchiveStateChanged(e._1, HttpArchiveStateInitializing),
          archiveIndex = archiveIndexCounter)
        publish(arcState.state)
        archiveStates += e._1 -> arcState
      }
    }
  }

  /**
    * Checks the conditions whether an HTTP archive can be started.
    *
    * @return '''true''' if all conditions are fulfilled; '''false'''
    *         otherwise
    */
  private def canStartHttpArchives: Boolean =
    unionArchiveAvailability == MediaFacade.MediaArchiveAvailable

  /**
    * Returns a map with archives that can now be started. These are archives
    * with login credentials that have not yet been started.
    *
    * @return a map with HTTP archives that can now be started
    */
  private def archivesToBeStarted: Map[String, HttpArchiveData] =
    configManager.archives filter { e =>
      StatesNotStarted.contains(archiveStates(e._1).state.state) &&
        realms.contains(e._2.realm)
    }

  /**
    * Resets the credentials of the specified realm and logs out all archives
    * associated with this realm.
    *
    * @param realm the name of the realm affected
    */
  private def removeRealmCredentials(realm: String): Unit = {
    realms -= realm
    if (canStartHttpArchives) {
      logoutArchives(realm)
    }
  }

  /**
    * Stops all archives after the realm they belong to has been logged out.
    *
    * @param realm the name of the realm
    */
  private def logoutArchives(realm: String): Unit = {
    configManager.archivesForRealm(realm) foreach { arc =>
      val name = arc.config.archiveName
      archiveStates(name).actors.keys foreach unregisterAndStopActor
      val newState = ArchiveStateData(actors = Map.empty, archiveIndex = 0,
        state = HttpArchiveStateChanged(name, HttpArchiveStateNotLoggedIn))
      if (archiveStates(name) != newState) {
        archiveStates += name -> newState
        publish(newState.state)
      }
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
