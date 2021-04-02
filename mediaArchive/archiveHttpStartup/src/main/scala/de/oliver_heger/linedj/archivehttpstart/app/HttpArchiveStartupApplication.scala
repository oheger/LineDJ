/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.archivehttpstart.app

import akka.actor.Actor.Receive
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.archivehttp.spi.HttpArchiveProtocol
import de.oliver_heger.linedj.archivehttp.{HttpArchiveStateConnected, HttpArchiveStateResponse, HttpArchiveStateServerError}
import de.oliver_heger.linedj.archivehttpstart.app.HttpArchiveStates._
import de.oliver_heger.linedj.platform.app.support.{ActorClientSupport, ActorManagement}
import de.oliver_heger.linedj.platform.app.{ApplicationAsyncStartup, ClientApplication, ClientApplicationContext}
import de.oliver_heger.linedj.platform.bus.Identifiable
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaArchiveAvailabilityEvent
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension.{ArchiveAvailabilityRegistration, ArchiveAvailabilityUnregistration}
import net.sf.jguiraffe.gui.app.ApplicationContext
import org.osgi.service.component.ComponentContext

import java.nio.file.Path
import java.security.Key
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object HttpArchiveStartupApplication {
  /** Property for the initialization timeout of the local archive. */
  final val PropArchiveInitTimeout = "media.initTimeout"

  /**
    * Property for the state request timeout for an HTTP archive. After an
    * archive has been started, it is sent a request to query its current
    * state. This request may time out and is then repeated. With this
    * property a timeout (in seconds) is specified.
    */
  final val PropArchiveStateRequestTimeout = "media.stateRequestTimeout"

  /**
    * Property defining the path where to store the super password file (the
    * encrypted file holding credentials of archives). If the property is
    * undefined, a default location in the user's home directory is used.
    */
  final val PropSuperPasswordFilePath = "media.superPasswordFile"

  /**
    * The name of the bean with the configuration manager for the managed HTTP
    * archives. This bean is created on application startup and stored in the
    * central bean context.
    */
  final val BeanConfigManager = "httpArchiveConfigManager"

  /** The name of the bean to startup an HTTP archive. */
  final val BeanArchiveStarter = "httpArchiveStarter"

  /** The default initialization timeout (in seconds). */
  private val DefaultInitTimeout = 10

  /** The default state request timeout (in seconds). */
  private val DefaultStateRequestTimeout = 60

  /**
    * An internally used data class that stores state information about a
    * single managed HTTP archive.
    *
    * @param state        the current state of this archive
    * @param actors       a map with the actors created for this archive
    * @param archiveIndex the index used for the archive when it was started
    * @param optKey       an optional key to decrypt this archive
    */
  private case class ArchiveStateData(state: HttpArchiveStateChanged,
                                      actors: Map[String, ActorRef],
                                      archiveIndex: Int,
                                      optKey: Option[Key]) {
    /**
      * Returns a flag whether this archive is currently started.
      *
      * @return a flag whether this archive is started
      */
    def isStarted: Boolean = actors.nonEmpty

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

    /**
      * Returns a new ''ArchiveStateData'' object based on this one, but with
      * properties reflecting an inactive state. This function can be used to
      * update an archive's state that switched from active to inactive.
      *
      * @return the state of an deactivated archive
      */
    def deactivate(newState: HttpArchiveStateChanged): ArchiveStateData =
      copy(state = newState, actors = Map.empty, archiveIndex = 0)
  }

  /**
    * A message class used internally to report the availability of a new
    * protocol service.
    *
    * When the declarative services runtime invokes the bind method for
    * protocol services, a message of this type is published on the event bus
    * to update the state of protocols in a thread-safe manner.
    *
    * @param protocol the protocol affected
    */
  private case class ProtocolAdded(protocol: HttpArchiveProtocol)

  /**
    * A message classed used internally to report that a protocol service has
    * been removed.
    *
    * This is the counter part to the [[ProtocolAdded]] message.
    *
    * @param protocol the protocol affected
    */
  private case class ProtocolRemoved(protocol: HttpArchiveProtocol)

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
  */
class HttpArchiveStartupApplication extends ClientApplication("httpArchiveStartup")
  with ApplicationAsyncStartup with Identifiable with ActorClientSupport
  with ActorManagement {

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

  /**
    * A map storing the HTTP protocols currently available using the protocol
    * name as key.
    */
  private var protocols = Map.empty[String, HttpArchiveProtocol]

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
    * Notifies this object that a protocol service is available. This method is
    * called by the declarative services runtime.
    *
    * @param protocol the protocol service
    */
  def addProtocol(protocol: HttpArchiveProtocol): Unit = {
    publish(ProtocolAdded(protocol))
  }

  /**
    * Notifies this object that a protocol service is no longer available. All
    * archives that use this protocol must be stopped. This method is called by
    * the declarative services runtime.
    */
  def removeProtocol(protocol: HttpArchiveProtocol): Unit = {
    publish(ProtocolRemoved(protocol))
  }

  /**
    * @inheritdoc This implementation directly registers a listener at the
    *             message bus. This is necessary to make sure that no protocol
    *             added events are missed (which can arrive as soon as
    *             ''activate()'' is invoked).
    */
  override def initClientContext(context: ClientApplicationContext): Unit = {
    super.initClientContext(context)
    messageBusRegistrationID.set(messageBus registerListener messageBusReceive)
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
    publish(ArchiveAvailabilityRegistration(componentID, archiveAvailabilityChanged))
    context
  }

  /**
    * Invokes the ''SuperPasswordStorageService'' provided to store information
    * about all the credentials and unlock passwords currently available. This
    * function is called to write the "super password" file.
    *
    * @param storageService the ''SuperPasswordStorageService''
    * @param target         the path to the target file
    * @param superPassword  the super password
    * @return a future with the result of the write operation
    */
  private[archivehttpstart] def saveArchiveCredentials(storageService: SuperPasswordStorageService, target: Path,
                                                       superPassword: String): Future[Path] = {
    val lockData = archiveStates.filter(_._2.optKey.isDefined)
      .map { e => (e._1, e._2.optKey.get) }
    storageService.writeSuperPasswordFile(target, superPassword, realms, lockData)
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
      m + (n -> ArchiveStateData(state, Map.empty, 0, None))
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

    case ProtocolAdded(protocol) =>
      protocols += (protocol.name -> protocol)
      // the notification may come in before the application is fully initialized
      if (configManager != null) {
        updateArchiveStates()
        triggerArchiveStartIfPossible()
      }

    case ProtocolRemoved(protocol) =>
      protocols -= protocol.name
      updateArchiveStates()

    case LoginStateChanged(realm, Some(creds)) =>
      removeRealmCredentials(realm)
      realms += realm -> creds
      configManager.archivesForRealm(realm) foreach (data => updateArchiveState(data.config.archiveName, data))
      triggerArchiveStartIfPossible()

    case LoginStateChanged(realm, None) =>
      removeRealmCredentials(realm)

    case LockStateChanged(archive, optKey@Some(_)) =>
      val currentState = archiveStates(archive)
      archiveStates += archive -> currentState.copy(optKey = optKey)
      updateArchiveState(archive, configManager.archives(archive))
      triggerArchiveStartIfPossible()

    case LockStateChanged(archive, None) =>
      val currentState = archiveStates(archive)
      archiveStates += archive -> currentState.copy(optKey = None)
      updateArchiveState(archive, configManager.archives(archive))

    case not@HttpArchiveStateChanged(_, HttpArchiveStateInitializing) =>
      queryAndPublishArchiveState(not)
  }

  /**
    * Stops all actors that have been created for the given archive.
    *
    * @param archiveData the data of the archive affected
    */
  private def stopArchiveActors(archiveData: ArchiveStateData): Unit = {
    archiveData.actors.keys foreach unregisterAndStopActor
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
    updateArchiveStates()
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
    * Checks whether the union archive is available as precondition for the
    * start of HTTP archives. If this is the case, all archives for which
    * login credentials are available are now started.
    */
  private def triggerArchiveStartIfPossible(): Unit = {
    if (isMediaArchiveAvailable && archivesToBeStarted.nonEmpty) {
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
    * started. For the first time an archive is started, the shared temporary
    * directory is cleared.
    *
    * @param mediaActors the object with the actors of the union archive
    */
  private def startupHttpArchives(mediaActors: MediaFacade.MediaFacadeActors): Unit = {
    if (isMediaArchiveAvailable) {
      val archiveStarter = archiveStarterBean
      archivesToBeStarted foreach { e =>
        archiveIndexCounter += 1
        val currentArchiveIndex = archiveIndexCounter
        val protocol = protocols(e._2.protocol)
        val futActors = archiveStarter.startup(mediaActors, e._2,
          clientApplicationContext.managementConfiguration, protocol, realms(e._2.realm.name), archiveStates(e._1).optKey,
          clientApplicationContext.actorFactory, currentArchiveIndex, clearTemp = currentArchiveIndex == 1)
        futActors onCompleteUIThread { triedResult =>
          handleArchiveStartupResult(e._1, currentArchiveIndex, triedResult)
        }
      }
    }
  }

  /**
    * Evaluates the result of starting up an archive. In case of success,
    * actors for the archive have been created and are now available as a map;
    * the data of the archive is updated accordingly, and its state is set to
    * initializing. Otherwise, the archive switches to an error state.
    *
    * @param name        the name of the archive affected
    * @param index       the index to be added to the actor names
    * @param triedResult the result of the startup operation
    */
  private def handleArchiveStartupResult(name: String, index: Int, triedResult: Try[Map[String, ActorRef]]): Unit = {
    val orgState = archiveStates(name)
    val nextData = triedResult match {
      case Success(actors) =>
        actors foreach (e => registerActor(e._1, e._2))
        orgState.copy(actors = actors, archiveIndex = index,
          state = HttpArchiveStateChanged(name, HttpArchiveStateInitializing))
      case Failure(exception) =>
        val state = HttpArchiveErrorState(HttpArchiveStateServerError(exception))
        orgState.deactivate(HttpArchiveStateChanged(name, state))
    }
    publish(nextData.state)
    archiveStates += name -> nextData
  }

  /**
    * Checks whether the media archive is currently available. This is the
    * precondition whether an HTTP archive can be started.
    *
    * @return '''true''' if the archive is available; '''false'''
    *         otherwise
    */
  private def isMediaArchiveAvailable: Boolean =
    unionArchiveAvailability == MediaFacade.MediaArchiveAvailable

  /**
    * Returns a map with archives that can now be started. These are archives
    * with login credentials that have not yet been started.
    *
    * @return a map with HTTP archives that can now be started
    */
  private def archivesToBeStarted: Map[String, HttpArchiveData] =
    configManager.archives filter { e =>
      !archiveStates(e._1).isStarted && archiveState(e._1, e._2).isStarted
    }

  /**
    * Resets the credentials of the specified realm and logs out all archives
    * associated with this realm.
    *
    * @param realm the name of the realm affected
    */
  private def removeRealmCredentials(realm: String): Unit = {
    realms -= realm
    updateArchiveStates()
  }

  /**
    * Calculates the state of an archive based on the current application
    * state. Here all possible conditions are checked.
    *
    * @param name the name of the archive
    * @param data the data of the archive
    * @return the new state of this archive
    */
  private def archiveState(name: String, data: HttpArchiveData): HttpArchiveState =
    if (!isMediaArchiveAvailable) HttpArchiveStateNoUnionArchive
    else if (!protocols.contains(data.protocol)) HttpArchiveStateNoProtocol
    else if (!realms.contains(data.realm.name)) HttpArchiveStateNotLoggedIn
    else if (data.encrypted && archiveStates(name).optKey.isEmpty) HttpArchiveStateLocked
    else HttpArchiveStateAvailable

  /**
    * Updates the state of the given archive after new information has arrived.
    * If there is a state transition, a message is published on the message
    * bus. If the archive is no longer started, its actors are released. Note
    * that an archive is not started by this method; this has to be triggered
    * explicitly.
    *
    * @param name the name of the archive affected
    * @param data the data of this archive
    */
  private def updateArchiveState(name: String, data: HttpArchiveData): Unit = {
    val nextState = archiveState(name, data)
    val state = HttpArchiveStateChanged(name, nextState)
    val lastData = archiveStates(name)
    if (state != lastData.state) {
      if (!nextState.isStarted) {
        if (lastData.isStarted) {
          stopArchiveActors(lastData)
        }
        archiveStates += name -> lastData.deactivate(state)
        publish(state)
      }
    }
  }

  /**
    * Updates the states of all archives after a change in the application
    * state. This method iterates over all archives and computes their state
    * anew. Change notifications are published if necessary. Note, however,
    * that this method does not start any archives. This has to be triggered
    * explicitly.
    */
  private def updateArchiveStates(): Unit = {
    configManager.archives foreach { e =>
      updateArchiveState(e._1, e._2)
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

  /**
    * Obtains the ''HttpArchiveStarter'' from the bean context.
    *
    * @return the ''HttpArchiveStarter''
    */
  private def archiveStarterBean: HttpArchiveStarter =
    getApplicationContext.getBeanContext.getBean(classOf[HttpArchiveStarter])

  /**
    * Returns the actor system in implicit scope. This is needed by the object
    * to materialize streams.
    *
    * @return the implicit actor system
    */
  private implicit def actorSystem: ActorSystem = clientApplicationContext.actorSystem
}
