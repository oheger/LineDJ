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

package de.oliver_heger.linedj.platform.audio.playlist.persistence

import de.oliver_heger.linedj.platform.app.ShutdownHandler.ShutdownObserver
import de.oliver_heger.linedj.platform.app.{ClientContextSupport, ShutdownHandler}
import de.oliver_heger.linedj.platform.audio2.playlist.{MediaFileInfoResolverService, Playlist, PlaylistService}
import de.oliver_heger.linedj.platform.bus.Identifiable
import de.oliver_heger.linedj.shared.actors.ActorFactory
import org.apache.logging.log4j.LogManager
import org.osgi.service.component.ComponentContext

import scala.compiletime.uninitialized
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success}

object PlaylistHandlerComponent:
  /** The logger. */
  private val log = LogManager.getLogger(classOf[PlaylistHandlerComponent])

  /**
    * A data class holding the services used by the component. This is used to
    * release all resources on deactivation.
    *
    * @param loadActorHandle the handle to the loader actor
    * @param saveActorHandle the handle to the save actor
    * @param config          the config of this handler
    */
  private case class Services(loadActorHandle: AutoCloseable,
                              saveActorHandle: PlaylistSaveActor.Handle,
                              config: PlaylistHandlerConfig)
end PlaylistHandlerComponent

/**
  * The main OSGi component implementing playlist handling functionality.
  *
  * This class is the central entry point into this playlist handler component.
  * It is started as declarative services component and injected some
  * dependencies.
  *
  * The class mainly delegates to other components to do its work, especially
  * a [[PlaylistLoaderActor]] to load the initial playlist and a
  * [[PlaylistSaveActor]] to monitor changes on the playlist and persisting
  * them if necessary. It creates these components on activation and performs
  * a graceful shutdown on deactivation. The shutdown logic is a bit tricky,
  * since unsaved changes on the playlist may need to be persisted before the
  * component can stop. For a regular shutdown of the application, this is 
  * achieved by extending the [[ShutdownObserver]] trait and implementing the
  * relevant logic when a shutdown is triggered. If the component is 
  * deactivated outside the regular shutdown process, it needs to block the
  * OSGi thread until all data has been saved.
  *
  * @param loaderFuncFactory  factory to create the loader function
  * @param saveServiceFactory factory to create a save service
  * @param loaderActorFactory factory to create a loader actor
  * @param saveActorFactory   factory to create a save actor
  */
class PlaylistHandlerComponent(val loaderFuncFactory: PlaylistLoader.Factory,
                               val saveServiceFactory: PlaylistSaveService.Factory,
                               val loaderActorFactory: PlaylistLoaderActor.Factory,
                               val saveActorFactory: PlaylistSaveActor.Factory)
  extends ClientContextSupport, Identifiable:
  def this() = this(
    PlaylistLoader.default,
    PlaylistSaveService.create,
    PlaylistLoaderActor.newInstance,
    PlaylistSaveActor.newInstance
  )

  import PlaylistHandlerComponent.*

  /**
    * Stores the dependency to the playlist service. The field is only accessed
    * from the OSGi management thread; therefore, no synchronization is needed.
    */
  private var playlistService: PlaylistService[Playlist, String] = uninitialized

  /**
    * Stores the dependency to the resolver service. This field is only
    * accessed from the OSGi management thread; therefore, no synchronization
    * is needed.
    */
  private var resolverService: MediaFileInfoResolverService = uninitialized

  /**
    * Stores the services when they have been created during activation. This
    * is needed to properly release resources on deactivation. This field is
    * only accessed from the OSGi management thread; therefore, no
    * synchronization is needed.
    */
  private var optServices: Option[Services] = None

  /**
    * Initializes the reference to the [[PlaylistService]]. This function is
    * called by the declarative services runtime.
    *
    * @param service the [[PlaylistService]]
    */
  def initPlaylistService(service: PlaylistService[Playlist, String]): Unit =
    playlistService = service

  /**
    * Initializes the reference to the [[MediaFileInfoResolverService]]. This
    * function is called by the declarative services runtime.
    *
    * @param service the [[MediaFileInfoResolverService]]
    */
  def initResolverService(service: MediaFileInfoResolverService): Unit =
    resolverService = service

  /**
    * @inheritdoc This implementation checks whether a valid configuration for
    *             persisting playlist information exists. If so, it creates the
    *             actors that implement playlist persistence.
    */
  override def activate(compContext: ComponentContext): Unit =
    super.activate(compContext)
    log.info("Activating PersistentPlaylistHandler.")
    optServices = startServicesIfPossible()

    optServices.foreach: services =>
      clientApplicationContext.messageBus.publish(
        ShutdownHandler.RegisterShutdownObserver(componentID, createShutdownObserver(services.saveActorHandle))
      )

  /**
    * @inheritdoc This implementation does cleanup. Note that the default
    *             shutdown logic is implemented using the [[ShutdownObserver]]
    *             mechanism. However, if this mechanism has not been used
    *             (because this component has been stopped manually), it still
    *             has to be ensured that the managed data is saved properly.
    *             Therefore, this function also closes the services and blocks
    *             until the shutdown future of the save actor completes. (This
    *             works well, even if the save actor has already been stopped.)
    */
  override def deactivate(componentContext: ComponentContext): Unit =
    log.info("Deactivating PersistentPlaylistHandler")
    optServices.foreach: services =>
      log.info("Stopping services.")
      services.loadActorHandle.close()
      services.saveActorHandle.close()
      clientApplicationContext.messageBus.publish(ShutdownHandler.RemoveShutdownObserver(componentID))
      waitForShutdown(services)

    super.deactivate(componentContext)

  /**
    * Creates a [[ShutdownObserver]] object that closes the save actor and 
    * triggers the provided notifier when the shutdown future completes. This
    * makes sure that during a regular shutdown all data gets persisted before
    * the application can shut down.
    *
    * @param saveActorHandle the handle to the save actor
    * @return the [[ShutdownObserver]]
    */
  private def createShutdownObserver(saveActorHandle: PlaylistSaveActor.Handle): ShutdownObserver =
    given ExecutionContext = clientApplicationContext.actorSystem.dispatcher

    (completionNotifier: ShutdownHandler.ShutdownCompletionNotifier) =>
      log.info("ShutdownObserver invoked. Closing PlaylistSaveActor.")
      saveActorHandle.close()

      saveActorHandle.shutdownFuture.onComplete: _ =>
        completionNotifier.shutdownComplete()

  /**
    * Tries to start the services if a valid configuration is available.
    *
    * @return an [[Option]] with the services that were started.
    */
  private def startServicesIfPossible(): Option[Services] =
    PlaylistHandlerConfig(clientApplicationContext.managementConfiguration) match
      case Success(config) =>
        log.info("Starting services for PersistentPlaylistHandler.")
        Some(createServices(config))
      case Failure(exception) =>
        log.error("No valid configuration for PersistentPlaylistHandler. Component is inactive.", exception)
        None

  /**
    * Creates the services used by this component based on the configuration.
    *
    * @param config the configuration of this application
    * @return the object with the services
    */
  private def createServices(config: PlaylistHandlerConfig): Services =
    given ActorFactory = clientApplicationContext.actorFactory

    val loaderFunc = loaderFuncFactory(using clientApplicationContext.actorSystem)
    val loaderParams = PlaylistLoaderActor.Params(
      config = config,
      messageBus = clientApplicationContext.messageBus,
      resolverService = resolverService,
      loaderFunc = loaderFunc
    )
    val loaderActorHandle = loaderActorFactory(loaderParams)

    val saveService = saveServiceFactory(playlistService)(using clientApplicationContext.actorSystem)
    val saveParams = PlaylistSaveActor.Params(
      messageBus = clientApplicationContext.messageBus,
      playlistService = playlistService,
      playlistSaveService = saveService,
      updateService = PlaylistWriteStateUpdateServiceImpl,
      writeConfig = PlaylistWriteStateUpdateService.PlaylistWriteConfig(
        pathPlaylist = config.pathPlaylist,
        pathPosition = config.pathPosition,
        autoSaveInterval = config.autoSaveInterval
      )
    )
    val saveActorHandle = saveActorFactory(saveParams)

    Services(loaderActorHandle, saveActorHandle, config)

  /**
    * Waits (in a blocking fashion) until the shutdown of the services (as 
    * indicated by the shutdown future) is complete; but not longer than the
    * configured shutdown timeout. This function is called on deactivation to
    * make sure that the OSGi runtime waits until unsaved state has been
    * persisted.
    *
    * @param services the services
    */
  private def waitForShutdown(services: Services): Unit =
    try
      Await.ready(services.saveActorHandle.shutdownFuture, services.config.shutdownTimeout)
      log.info("Services have been stopped.")
    catch
      case e =>
        log.warn("Timeout exceeded when waiting for the shutdown future.", e)
