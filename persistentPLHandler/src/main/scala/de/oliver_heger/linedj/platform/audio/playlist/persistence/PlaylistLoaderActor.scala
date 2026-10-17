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

import de.oliver_heger.linedj.platform.audio2.AudioPlayerCommands
import de.oliver_heger.linedj.platform.audio2.playlist.MediaFileInfoResolverService
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.shared.actors.{ActorFactory, BackoffActor}
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.{ExecutionContext, Promise}
import scala.util.Failure
import scala.util.Try
import scala.util.Success

/**
  * An actor implementation that is responsible for loading an already existing
  * playlist at application startup.
  *
  * When the persistent playlist handler bundle starts, it creates an actor
  * instance of this type. The actor checks for existing files with playlist
  * information and uses a [[PlaylistLoader]] function to load them.
  *
  * If the load operation was successful, the actor checks whether all media
  * files referenced in the playlist are actually available in the currently
  * connected media archive. This is done periodically with the help of a 
  * [[BackoffActor]] until all files could be resolved. Then the playlist is
  * activated by publishing a corresponding command on the system message bus.
  */
object PlaylistLoaderActor:
  /** The name of the actor instance. */
  final val ActorName = "PlaylistLoaderActor"

  /** The name of the backoff actor to check for availability of songs. */
  final val BackoffActorName = "CheckSongsAvailableActor"

  /**
    * A data class that holds the parameters required to create a new actor
    * instance.
    *
    * @param config          the configuration of the playlist handler
    * @param messageBus      the system message bus
    * @param loaderFunc      the function to load the playlist
    * @param resolverService the service to resolve media files
    * @param backoffFactory  the factory to create a backoff actor
    */
  final case class Params(config: PlaylistHandlerConfig,
                          messageBus: MessageBus,
                          loaderFunc: PlaylistLoader.LoaderFunc,
                          resolverService: MediaFileInfoResolverService,
                          backoffFactory: BackoffActor.Factory = BackoffActor.newInstance)

  /**
    * A trait for creating new instances of this actor class. The new instance
    * becomes active and starts immediately to load the persistent playlist if 
    * it exists.
    */
  trait Factory:
    /**
      * Creates a new actor instance and returns an [[AutoCloseable]] that can
      * be used to stop it.
      *
      * @param params       the parameters for the new actor instance
      * @param actorFactory the object to create new actors
      * @return a handle to stop the actor
      */
    def apply(params: Params)(using actorFactory: ActorFactory): AutoCloseable

  /**
    * An enumeration class defining the (internal) commands handled by the
    * loader actor.
    */
  private enum LoaderCommand:
    /**
      * A command reporting the result returned by the loader function. When
      * this command arrives, it is clear whether a playlist could be loaded or
      * not. The actor then decides whether to continue processing.
      *
      * @param result the result from the loader function
      */
    case LoaderFuncResult(result: Try[Option[AudioPlayerCommands]])

    /**
      * A command to stop the actor. This command is set when the actor handle
      * is closed.
      */
    case Stop
  end LoaderCommand

  /** A default factory for creating new actor instances. */
  final val newInstance: Factory = new Factory:
    override def apply(params: Params)(using actorFactory: ActorFactory): AutoCloseable =
      val behavior = setupActor(params)
      val actor = actorFactory.createTypedActor(behavior, ActorName)

      () => actor ! LoaderCommand.Stop

  /**
    * Returns the behavior for a new actor instance.
    *
    * @param params       the parameters for the new instance
    * @param actorFactory the actor factory
    * @return the behavior of the new actor instance
    */
  private def setupActor(params: Params)(using actorFactory: ActorFactory): Behavior[LoaderCommand] =
    Behaviors.setup: context =>
      given ExecutionContext = actorFactory.actorSystem.dispatcher

      /**
        * Triggers the function to load the playlist. When the result arrives, 
        * it is wrapped in a command and sent to this actor.
        */
      def loadPlaylist(): Unit =
        val config = PlaylistLoader.Config(
          playlistPath = params.config.pathPlaylist,
          positionPath = params.config.pathPosition,
          maxFileSize = params.config.maxFileSize
        )
        params.loaderFunc(config).onComplete: triedResult =>
          context.self ! LoaderCommand.LoaderFuncResult(triedResult)

      /**
        * Handles the result from the playlist loader function. If there is no
        * playlist for whatever reason, no further actions need to be taken.
        * Otherwise, the function creates a backoff actor with a function that
        * checks whether all songs in the playlist are available in the
        * connected archive.
        *
        * @param result the result from the loader function
        * @return an [[Option]] with the handle for the backoff actor
        */
      def handleLoadFunctionResult(result: Try[Option[AudioPlayerCommands]]): Option[BackoffActor.BackoffHandle] =
        result match
          case Failure(exception) =>
            context.log.error("Loading of the playlist failed.", exception)
            None
          case Success(None) =>
            context.log.info("No persistent playlist found at '{}'.", params.config.pathPlaylist)
            None
          case Success(Some(setPl: AudioPlayerCommands.SetPlaylist)) =>
            context.log.info("Playlist has been loaded. Checking for songs to be available.")
            Some(createBackoffActor(setPl))
          case Success(Some(cmd)) =>
            context.log.warn("Unexpected result from playlist loader function: {}.", cmd)
            None

      /**
        * Creates a backoff actor to check periodically whether all songs
        * referenced from the playlist are available in the archive.
        *
        * @param setPl the command to set the playlist
        * @return the handle of the backoff actor
        */
      def createBackoffActor(setPl: AudioPlayerCommands.SetPlaylist): BackoffActor.BackoffHandle =
        val mediaFileIDs = setPl.playlist.pendingSongs ::: setPl.playlist.playedSongs
        val checkFunc: BackoffActor.TaskFunc = () =>
          val promiseResult = Promise[BackoffActor.TaskResult]()
          params.resolverService.resolveFileIDs(mediaFileIDs): triedResult =>
            if triedResult.isSuccess then
              params.messageBus.publish(setPl)
              promiseResult.success(BackoffActor.TaskResult.Cancel)
            else
              promiseResult.success(BackoffActor.TaskResult.Backoff)
          promiseResult.future

        val backoffParams = BackoffActor.BackoffParameters(
          minBackoff = params.config.checkPlaylistMinBackoff,
          maxBackoff = params.config.checkPlaylistMaxBackoff,
          incrementFactor = params.config.checkPlaylistFactor,
          failureResult = BackoffActor.TaskResult.Backoff,
          taskFunc = checkFunc
        )
        params.backoffFactory(backoffParams, BackoffActorName)

      /**
        * The main command handler function.
        *
        * @param optBackoffHandle the optional handle of the backoff actor
        * @return the updated behavior
        */
      def handleCommand(optBackoffHandle: Option[BackoffActor.BackoffHandle]): Behavior[LoaderCommand] =
        Behaviors.receiveMessage:
          case LoaderCommand.LoaderFuncResult(result) =>
            handleCommand(handleLoadFunctionResult(result))
          case LoaderCommand.Stop =>
            optBackoffHandle.foreach(_.close())
            Behaviors.stopped

      loadPlaylist()
      handleCommand(None)
