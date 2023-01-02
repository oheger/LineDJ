/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio.impl

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.typed.{ActorRef, Scheduler}
import akka.util.Timeout
import de.oliver_heger.linedj.platform.app.support.{ActorClientSupport, ActorManagement}
import de.oliver_heger.linedj.platform.app.{ClientContextSupport, PlatformComponent}
import de.oliver_heger.linedj.platform.audio.actors.PlayerManagerActor.PlayerManagementCommand
import de.oliver_heger.linedj.platform.audio.actors.{AudioPlayerController, AudioPlayerManagerActor, PlayerManagerActor}
import de.oliver_heger.linedj.platform.comm.MessageBusListener
import de.oliver_heger.linedj.platform.comm.ServiceDependencies.{RegisterService, ServiceDependency, UnregisterService}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import de.oliver_heger.linedj.player.engine.PlaybackContextFactory
import org.apache.logging.log4j.LogManager
import org.osgi.service.component.ComponentContext

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future, TimeoutException}

object AudioPlatformComponent {
  /**
    * Constant for the service name of the playlist meta data resolver service.
    * A service registration with this name is created when the platform is up
    * and running. This allows components that depend on this service to track
    * its availability.
    */
  final val PlaylistMetaDataResolverServiceName = "lineDJ.playlistMetaDataResolver"

  /**
    * The name of the actor that manages the lifecycle of the audio player.
    */
  final val AudioPlayerManagementActorName = "lineDJ.audioPlayerManagementActor"

  /**
    * Constant for the service dependency registered for the playlist meta
    * data resolver service. This dependency can be tracked by clients of the
    * resolver to make sure that it is available before they publish consumer
    * registrations on the message bus.
    */
  final val PlaylistMetaDataResolverDependency =
    ServiceDependency(PlaylistMetaDataResolverServiceName)

  /** Prefix for the configuration keys for the audio platform. */
  final val PlatformConfigPrefix = "audio."

  /** Prefix for the configuration keys defining the audio player. */
  final val PlayerConfigPrefix = PlatformConfigPrefix + "player"

  /**
    * Configuration key that defines the timeout (in milliseconds) when
    * shutting down the audio platform. As a bunch of actors need to be closed
    * and temporary files removed from disk, this operation may take a while.
    * The platform waits for this amount of time until it is done, but not
    * longer.
    */
  final val PropShutdownTimeout = PlayerConfigPrefix + "shutdownTimeout"

  /** Prefix for configuration properties related to meta data. */
  final val MetaDataConfigPrefix = PlatformConfigPrefix + "metaData."

  /**
    * Configuration key for the chunk size for meta data queries. When
    * retrieving meta data for the songs in the current playlist, queries for
    * this number of songs are sent.
    */
  final val PropMetaDataQueryChunkSize = MetaDataConfigPrefix + "queryChunkSize"

  /**
    * Configuration key for the size of the meta data cache. Resolved meta data
    * for songs in the playlist is stored in a cache, so that it does not have
    * to be retrieved on each access. With this option the maximum size of the
    * cache can be specified. If the cache reaches its limit, older entries are
    * removed.
    */
  final val PropMetaDataCacheSize = MetaDataConfigPrefix + "cacheSize"

  /**
    * Configuration key for the timeout for meta data requests. If a request
    * for meta data takes longer than this value (in milliseconds), the
    * request is considered a failure, and dummy meta data is used for the
    * songs affected.
    */
  final val PropMetaDataRequestTimeout = MetaDataConfigPrefix + "requestTimeout"

  /** The default value for the shutdown timeout. */
  final val DefaultShutdownTimeout = 3.seconds

  /** Default meta data query chunk size. */
  final val DefaultMetaDataQueryChunkSize = 20

  /** Default cache size for resolved meta data. */
  final val DefaultMetaDataCacheSize = 1000

  /** Default timeout for meta data requests. */
  final val DefaultMetaDataRequestTimeout = 30.seconds
}

/**
  * A declarative services component representing the audio platform.
  *
  * This component is started automatically by the declarative services
  * runtime when all dependencies are satisfied. It is responsible for
  * creating and registering controller objects (and corresponding OSGi
  * services) that control the playback of audio based on commands sent to the
  * central message bus. Of course, correct cleanup needs to be done when the
  * component is deactivated.
  *
  * @param playerFactory the factory for creating the audio player
  */
class AudioPlatformComponent(private[impl] val playerFactory: AudioPlayerFactory)
  extends PlatformComponent with ClientContextSupport with ActorManagement with ActorClientSupport {

  import AudioPlatformComponent._

  /** The logger. */
  private val log = LogManager.getLogger(getClass)

  /** The actors for the media facade. */
  private var mediaFacadeActors: MediaFacadeActors = _

  /** The actor managing the audio player lifecycle. */
  private var optManagementActor: Option[ActorRef[PlayerManagementCommand]] = None

  /**
    * Stores playback context factories that are added before the
    * creation of the audio player. They have to be stored, so that they can
    * be added later when the player is created. Note that no special
    * synchronization is needed: All access happens in the OSGi management
    * thread.
    */
  private var playbackContextFactories = List.empty[PlaybackContextFactory]

  /** The meta data resolver object. */
  private var playlistMetaDataResolver: Option[PlaylistMetaDataResolver] = None

  /** The message bus listener registration ID for the meta data resolver. */
  private var metaDataResolverRegistrationID = 0

  /**
    * Creates a new instance of ''AudioPlatformComponent'' that sets default
    * values for all dependencies.
    *
    * @return the new instance
    */
  def this() = this(new AudioPlayerFactory)

  /**
    * Initializes the object with the actors for the media facade. This method
    * is called by the declarative services runtime.
    *
    * @param mediaFacadeActors the media facade actors
    */
  def initFacadeActors(mediaFacadeActors: MediaFacadeActors): Unit = {
    this.mediaFacadeActors = mediaFacadeActors
  }

  /**
    * Notifies this object that a service of type ''PlaybackContextFactory''
    * has been bound. This method is called by the declarative services
    * runtime. Note that it can be called before or after the activation of
    * this component. This implementation makes sure, that the service is
    * tracked and eventually passed to the management actor.
    *
    * @param factory the ''PlaybackContextFactory''
    */
  def addPlaybackContextFactory(factory: PlaybackContextFactory): Unit = {
    log.info("Adding PlaybackContextFactory.")
    optManagementActor match {
      case Some(actor) =>
        actor ! PlayerManagerActor.AddPlaybackContextFactories(List(factory))
      case None =>
        playbackContextFactories = factory :: playbackContextFactories
    }
  }

  /**
    * Notifies this object that the specified ''PlaybackContextFactory''
    * service has been removed. This method is called by the declarative
    * services runtime. Again, this can happen before or after the activation
    * of this component.
    *
    * @param factory the ''PlaybackContextFactory''
    */
  def removePlaybackContextFactory(factory: PlaybackContextFactory): Unit = {
    log.info("Removing PlaybackContextFactory.")
    optManagementActor match {
      case Some(actor) =>
        actor ! PlayerManagerActor.RemovePlaybackContextFactories(List(factory))
      case None =>
        playbackContextFactories = playbackContextFactories filterNot (_ == factory)
    }
  }

  /**
    * @inheritdoc This implementation creates the management actor, which in
    *             turn creates and registers the audio player.
    */
  override def activate(compContext: ComponentContext): Unit = {
    super.activate(compContext)

    log.info("Activating audio platform.")

    val managementActor = clientApplicationContext.actorFactory.createActor(
      AudioPlayerManagerActor(clientApplicationContext.messageBus)(playerControllerCreationFunc),
      AudioPlayerManagementActorName)
    if (playbackContextFactories.nonEmpty) {
      managementActor ! PlayerManagerActor.AddPlaybackContextFactories(playbackContextFactories)
    }
    optManagementActor = Some(managementActor)

    val metaDataResolver = createPlaylistMetaDataResolver()
    metaDataResolverRegistrationID =
      registerService(metaDataResolver, PlaylistMetaDataResolverDependency)
    managementActor ! PlayerManagerActor.PublishAfterCreation(metaDataResolver.playerStateChangeRegistration)
    playlistMetaDataResolver = Some(metaDataResolver)
  }

  /**
    * @inheritdoc This implementation cleans up all registrations done before.
    */
  override def deactivate(componentContext: ComponentContext): Unit = {
    playlistMetaDataResolver foreach { r =>
      clientApplicationContext.messageBus publish r.playerStateChangeRegistration.unRegistration
    }
    unregisterService(PlaylistMetaDataResolverDependency, metaDataResolverRegistrationID)
    closeAudioPlayerManagerActor()
    log.info("Audio platform deactivated.")

    super.deactivate(componentContext)
  }

  /**
    * The function to create the [[AudioPlayerController]].
    *
    * @return a ''Future'' with the [[AudioPlayerController]]
    */
  private def playerControllerCreationFunc(): Future[AudioPlayerController] = {
    playerFactory.createAudioPlayer(clientApplicationContext.managementConfiguration,
      PlayerConfigPrefix, mediaFacadeActors.mediaManager, this) map { player =>
      new AudioPlayerController(player, clientApplicationContext.messageBus)
    }
  }

  /**
    * Creates the service to resolve meta data for songs in the playlist.
    *
    * @return the ''PlaylistMetaDataResolver''
    */
  private[impl] def createPlaylistMetaDataResolver(): PlaylistMetaDataResolver = {
    val conf = clientApplicationContext.managementConfiguration
    implicit val ec: ExecutionContextExecutor = clientApplicationContext.actorSystem.dispatcher
    new PlaylistMetaDataResolver(metaDataActor = mediaFacadeActors.mediaManager,
      bus = clientApplicationContext.messageBus,
      queryChunkSize = conf.getInt(PropMetaDataQueryChunkSize, DefaultMetaDataQueryChunkSize),
      cacheSize = conf.getInt(PropMetaDataCacheSize, DefaultMetaDataCacheSize),
      requestTimeout = conf.getLong(PropMetaDataRequestTimeout,
        DefaultMetaDataRequestTimeout.toMillis).millis)
  }

  /**
    * Registers a service as message bus listener and creates the corresponding
    * OSGi service registration.
    *
    * @param service    the service
    * @param dependency the dependency for the OSGi service
    * @return the message bus registration ID
    */
  private def registerService(service: MessageBusListener,
                              dependency: ServiceDependency): Int = {
    val regID = clientApplicationContext.messageBus registerListener service.receive
    clientApplicationContext.messageBus publish RegisterService(dependency)
    regID
  }

  /**
    * Removes registrations for a service.
    *
    * @param dependency the service dependency
    * @param regID      the message bus registration ID
    */
  private def unregisterService(dependency: ServiceDependency, regID: Int): Unit = {
    clientApplicationContext.messageBus publish UnregisterService(dependency)
    clientApplicationContext.messageBus removeListener regID
  }

  /**
    * Closes the audio player and waits until all actors involved have been
    * closed.
    */
  private def closeAudioPlayerManagerActor(): Unit = {
    optManagementActor foreach { actor =>
      log.info("Closing AudioPlayerManagerActor.")
      val shutdownTimeout = fetchShutdownTimeout()
      implicit val timeout: Timeout = Timeout(shutdownTimeout)
      implicit val scheduler: Scheduler = clientApplicationContext.actorSystem.toTyped.scheduler
      val futureAck = actor.ask[PlayerManagerActor.CloseAck] { ref =>
        PlayerManagerActor.Close(ref, timeout)
      }

      try {
        Await.ready(futureAck, shutdownTimeout)
      } catch {
        case _: TimeoutException =>
          log.warn("Timeout when shutting down audio player!")
      }
    }
  }

  /**
    * Obtains the value for the shutdown timeout from the configuration.
    *
    * @return the shutdown timeout
    */
  private def fetchShutdownTimeout(): FiniteDuration =
    clientApplicationContext.managementConfiguration.getInt(PropShutdownTimeout,
      DefaultShutdownTimeout.toMillis.toInt).millis
}
