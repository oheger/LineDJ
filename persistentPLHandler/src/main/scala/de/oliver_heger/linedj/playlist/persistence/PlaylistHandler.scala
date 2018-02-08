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

package de.oliver_heger.linedj.playlist.persistence

import akka.actor.Actor.Receive
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout
import de.oliver_heger.linedj.io.CloseRequest
import de.oliver_heger.linedj.platform.app.{ClientContextSupport, ShutdownHandler}
import de.oliver_heger.linedj.platform.audio.playlist.service.PlaylistService
import de.oliver_heger.linedj.platform.audio.{AudioPlayerStateChangeRegistration, AudioPlayerStateChangeUnregistration, AudioPlayerStateChangedEvent, SetPlaylist}
import de.oliver_heger.linedj.platform.bus.Identifiable
import de.oliver_heger.linedj.platform.comm.{MessageBus, MessageBusListener}
import de.oliver_heger.linedj.platform.mediaifc.ext.AvailableMediaExtension.{AvailableMediaRegistration, AvailableMediaUnregistration}
import de.oliver_heger.linedj.player.engine.PlaybackProgressEvent
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediumID}
import org.osgi.service.component.ComponentContext
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
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
  */
class PlaylistHandler extends ClientContextSupport with MessageBusListener with Identifiable {

  import PlaylistHandler._

  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

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

  /** Stores the playlist that was loaded and needs to be persisted. */
  private var loadedPlaylist: SetPlaylist = _

  /** A set with the IDs of the media that are currently available. */
  private var availableMediaIDs = Set.empty[MediumID]

  /**
    * A set with the IDs of media referenced by the playlist. The playlist is
    * activated only if all these media are available. If this field is
    * ''None'', the playlist either has not yet been loaded or has already been
    * activated.
    */
  private var referencedMediaIDs: Option[Set[MediumID]] = None

  /**
    * @inheritdoc This implementation triggers the load of the persistent
    *             playlist. When this is done, further actions are executed.
    */
  override def activate(compContext: ComponentContext): Unit = {
    super.activate(compContext)
    log.info("Activating PlaylistHandler.")

    PlaylistHandlerConfig(clientApplicationContext.managementConfiguration) match {
      case Failure(ex) =>
        log.error("Could not read configuration! Playlist handler is not active.", ex)
      case Success(config) =>
        handleActivation(config)
    }
  }

  /**
    * @inheritdoc This implementation does cleanup. Note that shutdown logic is
    *             implemented as reaction on a ''Shutdown'' message.
    */
  override def deactivate(componentContext: ComponentContext): Unit = {
    bus removeListener busRegistrationID
    bus publish AudioPlayerStateChangeUnregistration(componentID)
    removeAvailableMediaRegistration()
    stateWriterActor foreach clientApplicationContext.actorSystem.stop
    super.deactivate(componentContext)
  }

  /**
    * The function for handling messages published on the message bus.
    */
  override def receive: Receive = {
    case LoadedPlaylist(setPlaylist) =>
      loadedPlaylist = setPlaylist
      referencedMediaIDs = Some(PlaylistService.toSongList(setPlaylist.playlist)
        .foldLeft(Set.empty[MediumID])(_ + _.mediumID))
      activatePlaylistIfPossible()
      bus publish AudioPlayerStateChangeRegistration(componentID, handlePlaylistStateChange)

    case ev: PlaybackProgressEvent =>
      sendMsgToStateWriter(ev)

    case ShutdownHandler.Shutdown(_) =>
      log.info("Received Shutdown message.")
      stateWriterActor foreach shutdownStateWriterActor
  }

  /**
    * Performs initialization during activation of this component.
    *
    * @param config the configuration
    */
  private def handleActivation(config: PlaylistHandlerConfig): Unit = {
    handlerConfig = config
    stateWriterActor = Some(clientApplicationContext.actorFactory.createActor(
      PlaylistStateWriterActor(config.pathPlaylist, config.pathPosition,
        config.autoSaveInterval), WriterActorName))
    busRegistrationID = bus registerListener receive
    bus publish AvailableMediaRegistration(componentID, handleAvailableMedia)
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
    val loaderActor = clientApplicationContext.actorFactory.createActor(Props[LoadPlaylistActor],
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
    availableMediaIDs = availableMedia.media.keySet
    activatePlaylistIfPossible()
  }

  /**
    * Checks whether the playlist can be activated. If so, this is done now.
    */
  private def activatePlaylistIfPossible(): Unit = {
    if (referencedMediaIDs exists (_.forall(availableMediaIDs.contains))) {
      log.info("Activating playlist.")
      bus publish loadedPlaylist
      removeAvailableMediaRegistration()
      referencedMediaIDs = None
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
    * Sends a close request to the state writer actor and handles its response.
    * This makes sure that recent updates on the playlist state are written to
    * disk. A shutdown confirmation is sent when this is done.
    *
    * @param act the state writer actor
    */
  private def shutdownStateWriterActor(act: ActorRef): Unit = {
    implicit val ec: ExecutionContext = clientApplicationContext.actorSystem.dispatcher
    implicit val timeout: Timeout = Timeout(handlerConfig.shutdownTimeout)
    val futAck = act ? CloseRequest
    futAck onComplete { t =>
      t match {
        case Failure(e) =>
          log.warn("Waiting for CloseAck of state writer actor failed!", e)
        case _ =>
          log.debug("State writer actor closed.")
      }
      bus publish ShutdownHandler.ShutdownDone(componentID)
    }
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
