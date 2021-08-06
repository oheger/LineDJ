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

package de.oliver_heger.linedj.playlist.persistence

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.Actor.Receive
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout
import de.oliver_heger.linedj.io.CloseRequest
import de.oliver_heger.linedj.platform.app.ShutdownHandler.{ShutdownCompletionNotifier, ShutdownObserver}
import de.oliver_heger.linedj.platform.app.{ClientContextSupport, ShutdownHandler}
import de.oliver_heger.linedj.platform.audio.{AudioPlayerStateChangeUnregistration, AudioPlayerStateChangedEvent}
import de.oliver_heger.linedj.platform.bus.Identifiable
import de.oliver_heger.linedj.platform.comm.{MessageBus, MessageBusListener}
import de.oliver_heger.linedj.platform.mediaifc.ext.AvailableMediaExtension.AvailableMediaUnregistration
import de.oliver_heger.linedj.player.engine.PlaybackProgressEvent
import de.oliver_heger.linedj.shared.archive.media.AvailableMedia
import org.osgi.service.component.ComponentContext
import org.slf4j.LoggerFactory

import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.util.{Failure, Success}

object PlaylistHandler {
  /** The name of the actor for loading the playlist. */
  private val LoaderActorName = "persistentPlaylistLoaderActor"

  /** The name of the actor for saving the playlist state. */
  private val WriterActorName = "persistentPlaylistStateWriterActor"
}

/**
  * The main OSGi component implementing playlist handling functionality.
  *
  * This class is the central entry point into this playlist handler component.
  * It is started as declarative services component and injected some
  * dependencies.
  *
  * After being activated, it tries to read information about a persisted
  * playlist. If this is successful and if all media referenced by the playlist
  * are available, commands are sent via the message bus to set this playlist.
  * The class is also a consumer for ''AvailableMedia'' messages. Thus, it can
  * find out when all media referenced by the playlist are available; when
  * this is the case, the playlist is passed to the audio platform.
  *
  * The handler is also notified about changes on the audio player state and
  * playback progress events. This information is used to update the managed
  * playlist state and save data to disk if there are relevant changes (as a
  * kind of auto-save functionality to avoid data loss if the application
  * shuts down in an unexpected way). The updated state is also saved before
  * the component is deactivated.
  *
  * @param updateService the update service used by this instance
  */
class PlaylistHandler private[persistence](val updateService: PersistentPlaylistStateUpdateService)
  extends ClientContextSupport with MessageBusListener with Identifiable with ShutdownObserver {

  /**
    * Creates a new instance of ''PlaylistHandler'' with default dependencies.
    *
    * @return the new instance
    */
  def this() = this(PersistentPlaylistStateUpdateServiceImpl)

  import PlaylistHandler._

  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

  /** A flag to record whether shutdown handling was done. */
  private val shutdownDone = new AtomicBoolean

  /**
    * Holds a reference to the actor for persisting changes on the playlist.
    * This is an option because the actor is created only if the configuration
    * is valid.
    *
    * Note: This field is initialized in the OSGi management thread, and then
    * an action on the UI thread is triggered (a message bus registration).
    * Therefore, it is visible for both threads, and no additional
    * synchronization is required.
    */
  private var stateWriterActor: Option[ActorRef] = None

  /**
    * Stores the configuration of this handler.
    *
    * Note: The field is accessed only in ''activate()'' and ''deactivate()''
    * (in the OSGi management thread). Therefore, no synchronization is
    * required.
    */
  private var handlerConfig: PlaylistHandlerConfig = _

  /** Stores the ID of the registration at the message bus. */
  private var busRegistrationID = 0

  /** The state managed by this component. */
  private var currentState = PersistentPlaylistStateUpdateServiceImpl.InitialState

  /**
    * @inheritdoc This implementation performs some initialization on the OSGi
    *             management thread. It obtains the configuration for this
    *             object and passes it via the message bus to the UI thread;
    *             from there, further actions are executed.
    */
  override def activate(compContext: ComponentContext): Unit = {
    super.activate(compContext)
    log.info("Activating PlaylistHandler.")

    PlaylistHandlerConfig(clientApplicationContext.managementConfiguration) match {
      case Failure(ex) =>
        log.error("Could not read configuration! Playlist handler is not active.", ex)
      case Success(config) =>
        busRegistrationID = bus registerListener receive
        bus publish ShutdownHandler.RegisterShutdownObserver(componentID, this)
        bus publish config
    }
  }

  /**
    * @inheritdoc This implementation does cleanup. Note that the default
    *             shutdown logic is implemented as reaction on a ''Shutdown''
    *             message. However, if this message has not been received
    *             (because this component has been stopped manually), it still
    *             has to be ensured that the managed data is saved properly.
    */
  override def deactivate(componentContext: ComponentContext): Unit = {
    log.info("Deactivating PlaylistHandler.")
    if (busRegistrationID != 0) {
      bus removeListener busRegistrationID
      bus publish AudioPlayerStateChangeUnregistration(componentID)
      removeAvailableMediaRegistration()
      bus publish ShutdownHandler.RemoveShutdownObserver(componentID)

      if (!shutdownDone.get()) {
        handleShutdownOnDeactivation()
      }
    }

    super.deactivate(componentContext)
  }

  /**
    * The function for handling messages published on the message bus.
    */
  override def receive: Receive = {
    case config: PlaylistHandlerConfig =>
      handleActivation(config)

    case LoadedPlaylist(setPlaylist) =>
      sendMsgToStateWriter(setPlaylist)
      updateState(updateService.handlePlaylistLoaded(setPlaylist, handlePlaylistStateChange))

    case ev: PlaybackProgressEvent =>
      sendMsgToStateWriter(ev)
  }

  /**
    * @inheritdoc This implementation triggers a graceful shutdown of this
    *             component.
    */
  override def triggerShutdown(completionNotifier: ShutdownHandler.ShutdownCompletionNotifier): Unit = {
    log.info("triggerShutdown() invoked.")
    stateWriterActor foreach (shutdownStateWriterActor(_, completionNotifier))
  }

  /**
    * Stops the state writer actor. This has to be done in any case if this
    * actor has been created before; no matter whether there is a regular
    * shutdown or a hard deactivation. ''Note'': The method is exposed for
    * testability.
    *
    * @param act the actor to be stopped
    */
  private[persistence] def stopStateWriterActor(act: ActorRef): Unit = {
    clientApplicationContext.actorSystem.stop(act)
  }

  /**
    * Performs initialization during activation of this component.
    *
    * @param config the configuration
    */
  private def handleActivation(config: PlaylistHandlerConfig): Unit = {
    handlerConfig = config
    val writeConfig = PlaylistWriteConfig(config.pathPlaylist, config.pathPosition,
      config.autoSaveInterval)
    stateWriterActor = Some(clientApplicationContext.actorFactory.createActor(
      PlaylistStateWriterActor(writeConfig), WriterActorName))
    updateState(updateService.handleActivation(componentID, handleAvailableMedia))
    triggerLoadOfPersistentPlaylist(config)
  }

  /**
    * Creates a [[LoadPlaylistActor]] and invokes it to load the files with the
    * latest state of the playlist. The information required for this are
    * fetched from the configuration.
    *
    * @param config the configuration for this handler
    */
  private def triggerLoadOfPersistentPlaylist(config: PlaylistHandlerConfig): Unit = {
    val loaderActor = clientApplicationContext.actorFactory.createActor(Props[LoadPlaylistActor](),
      LoaderActorName)
    loaderActor ! LoadPlaylistActor.LoadPlaylistData(config.pathPlaylist,
      config.pathPosition, config.maxFileSize, bus)
  }

  /**
    * Callback for notifications about a changed audio player state. This
    * method forwards the updated state to the state writer actor.
    *
    * @param event the state change event
    */
  private def handlePlaylistStateChange(event: AudioPlayerStateChangedEvent): Unit = {
    sendMsgToStateWriter(event.state)
  }

  /**
    * The consumer function for ''AvailableMedia'' notifications. This
    * implementation checks whether a playlist has been read and whether it
    * contains media that are not yet available. If all these media are now
    * available, the playlist is activated.
    *
    * @param availableMedia the ''AvailableMedia'' data
    */
  private def handleAvailableMedia(availableMedia: AvailableMedia): Unit = {
    updateState(updateService.handleNewAvailableMedia(availableMedia))
  }

  /**
    * Updates the state managed by this component and sends out corresponding
    * messages on the message bus.
    *
    * @param update the update operation
    */
  private def updateState(update: PersistentPlaylistStateUpdateServiceImpl
  .StateUpdate[Iterable[Any]]): Unit = {
    val (nextState, messages) = update(currentState)
    currentState = nextState
    messages foreach bus.publish
  }

  private def handleShutdownOnDeactivation(): Unit = {
    // The actor has been created and used in the UI thread. As we are now
    // on the OSGi management thread, we have to synchronize to access it
    // safely.
    val optStateWriter = this.synchronized(stateWriterActor)
    optStateWriter foreach { actor =>
      log.info("No shutdown message received. Saving state in deactivate().")
      val futClose = triggerStateWriterActorClose(actor)
      try {
        Await.ready(futClose, handlerConfig.shutdownTimeout)
      } catch {
        case e: TimeoutException =>
          log.warn(s"Could not close state writer actor within the timeout of ${handlerConfig.shutdownTimeout}.",
            e)
      }
      stopStateWriterActor(actor)
    }
  }

  /**
    * Sends the specified message to the state writer actor.
    *
    * @param msg the message to be sent
    */
  private def sendMsgToStateWriter(msg: Any): Unit = {
    stateWriterActor foreach (_ ! msg)
  }

  /**
    * Returns the implicit execution context for dealing with futures.
    *
    * @return the implicit execution context
    */
  private implicit def executionContext: ExecutionContext = clientApplicationContext.actorSystem.dispatcher

  /**
    * Sends a ''CloseRequest'' message to the state writer actor to trigger the
    * saving of the current playlist state before this component gets
    * deactivated.
    *
    * @param act the state writer actor
    * @return the ''Future'' for the ACK response.
    */
  private def triggerStateWriterActorClose(act: ActorRef): Future[Any] = {
    log.info("Triggering close of state writer actor.")
    implicit val timeout: Timeout = Timeout(handlerConfig.shutdownTimeout)
    act ? CloseRequest
  }

  /**
    * Sends a close request to the state writer actor and handles its response.
    * This makes sure that recent updates on the playlist state are written to
    * disk. A shutdown confirmation is sent when this is done.
    *
    * @param act                the state writer actor
    * @param completionNotifier the shutdown completion notifier
    */
  private def shutdownStateWriterActor(act: ActorRef, completionNotifier: ShutdownCompletionNotifier): Unit =
    triggerStateWriterActorClose(act) onComplete { t =>
      t match {
        case Failure(e) =>
          log.warn("Waiting for CloseAck of state writer actor failed!", e)
        case _ =>
          log.info("State writer actor closed.")
      }
      shutdownDone set true
      stopStateWriterActor(act)
      completionNotifier.shutdownComplete()
      log.info("Shutdown completed.")
    }

  /**
    * Removes the registration for the available media state. This is done
    * after the playlist has been set and also when the component is
    * deactivated. (Note that it does not cause problems to remove a
    * registration multiple times.)
    */
  private def removeAvailableMediaRegistration(): Unit = {
    bus publish AvailableMediaUnregistration(componentID)
  }

  /**
    * Convenience method to access the message bus.
    *
    * @return the message bus
    */
  private def bus: MessageBus = clientApplicationContext.messageBus
}
