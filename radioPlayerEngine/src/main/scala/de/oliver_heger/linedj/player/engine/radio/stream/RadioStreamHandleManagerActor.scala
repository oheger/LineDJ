/*
 * Copyright 2015-2024 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine.radio.stream

import de.oliver_heger.linedj.player.engine.actors.ScheduledInvocationActor
import de.oliver_heger.linedj.player.engine.radio.{CurrentMetadata, RadioSource}
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

/**
  * A module implementing an actor that manages [[RadioStreamHandle]]
  * instances.
  *
  * This actor implementation is responsible for creating radio streams and
  * associated [[RadioStreamHandle]] objects. In addition, it implements some
  * caching on handles that have been used before in metadata checks. The goal
  * here is to use the same radio stream instances for playing audio data that
  * have been used before when doing metadata checks. Obviously, it can happen
  * that checks for metadata exclusions succeed, but after opening a new
  * connection (when creating a new radio stream), the radio source plays again
  * forbidden data. (In the concrete case, advertisements were played when
  * connecting anew to the radio stream.) To prevent this, this actor acts as a
  * cache for radio stream handle instances that have actually been closed. It
  * keeps them open for a while until it finally closes them. If the same radio
  * source is opened again during this time, the same stream handle can be
  * reused. To enable this caching mechanism, instead of closing stream
  * handles, they have to be passed to this actor, so that they can be put into
  * the temporary cache.
  */
object RadioStreamHandleManagerActor:
  /**
    * A data class collecting the parameters required to create a new instance
    * of [[RadioStreamHandle]].
    *
    * @param streamSource the radio source to be played
    * @param streamName   the name for the radio stream
    */
  case class GetStreamHandleParameters(streamSource: RadioSource,
                                       streamName: String)

  /**
    * A data class defining the response for a request to obtain a stream
    * handle. The object mainly contains the handle, which was either newly
    * created or obtained from the cache. Since the creation may fail, this is
    * a ''Try''.
    *
    * @param source            the radio source to be played
    * @param triedStreamHandle the [[RadioStreamHandle]] reference
    * @param optLastMetadata   the last metadata received for this radio stream
    *                          if available
    */
  case class GetStreamHandleResponse(source: RadioSource,
                                     triedStreamHandle: Try[RadioStreamHandle],
                                     optLastMetadata: Option[CurrentMetadata])

  /**
    * The base trait for the commands processed by this actor implementation.
    */
  sealed trait RadioStreamHandleCommand

  /**
    * A command to request a [[RadioStreamHandle]] for a specific radio source
    * from this actor instance.
    *
    * @param params  the parameters of the stream handle
    * @param replyTo the actor to send the response to
    */
  case class GetStreamHandle(params: GetStreamHandleParameters,
                             replyTo: ActorRef[GetStreamHandleResponse]) extends RadioStreamHandleCommand

  /**
    * A command that passes a [[RadioStreamHandle]] for a specific radio source
    * to this manager actor which is no longer used. The stream handle is put
    * into the cache for the configured cache time. If it is not requested
    * again within this time, it is closed. Together with the handle, some
    * additional data has to be provided that is needed when reusing it from
    * the cache. Optionally, the latest known metadata for this source can be
    * specified. This is important if the stream is actually reused, since
    * there is no guarantee that an update of metadata happens sometime soon.
    *
    * @param source          the radio source
    * @param streamHandle    the [[RadioStreamHandle]] to release
    * @param optLastMetadata the last metadata for this stream if available
    */
  case class ReleaseStreamHandle(source: RadioSource,
                                 streamHandle: RadioStreamHandle,
                                 optLastMetadata: Option[CurrentMetadata]) extends RadioStreamHandleCommand

  /**
    * A command telling this actor to stop itself. Before terminating, the
    * actor tries to gracefully close all stream handles that are currently
    * contained in the cache.
    */
  case object Stop extends RadioStreamHandleCommand

  /**
    * An internal command telling this actor that the stream handle for a
    * specific radio source should now be removed from the cache if it is still
    * contained there.
    *
    * @param source the radio source affected
    */
  private case class CacheTimeEnd(source: RadioSource) extends RadioStreamHandleCommand

  /**
    * A data class collecting the information that needs to be stored in the
    * cache for stream handles.
    *
    * @param streamHandle the actual stream handle
    * @param optMetadata  an option with the last known metadata
    */
  private case class CacheData(streamHandle: RadioStreamHandle,
                               optMetadata: Option[CurrentMetadata])

  /**
    * A data class representing the state of this actor.
    *
    * @param streamBuilder the object to create radio streams
    * @param handleFactory the factory for creating stream handles
    * @param scheduler     the scheduled invocation actor
    * @param cacheTime     the time stream actors remain in the cache before
    *                      they are finally closed
    * @param bufferSize    the size of the playback buffer
    * @param cache         the map with the cache of stream actors
    */
  private case class RadioStreamHandleState(streamBuilder: RadioStreamBuilder,
                                            handleFactory: RadioStreamHandle.Factory,
                                            scheduler: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
                                            cacheTime: FiniteDuration,
                                            bufferSize: Int,
                                            cache: Map[RadioSource, CacheData])

  /**
    * A factory trait allowing the creation of new instances of this actor
    * implementation.
    */
  trait Factory:
    /**
      * Returns the ''Behavior'' for creating new actor instances.
      *
      * @param streamBuilder the object to create radio streams
      * @param handleFactory the factory for creating a stream handle
      * @param scheduler     the scheduled invocation actor
      * @param cacheTime     the time stream actors remain in the cache before
      *                      they are finally closed
      * @param bufferSize    the size of the playback buffer to create for new
      *                      radio streams
      * @return the ''Behavior'' to create a new instance
      */
    def apply(streamBuilder: RadioStreamBuilder,
              handleFactory: RadioStreamHandle.Factory,
              scheduler: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
              cacheTime: FiniteDuration,
              bufferSize: Int = RadioStreamBuilder.DefaultBufferSize): Behavior[RadioStreamHandleCommand]

  /**
    * A default implementation of the [[Factory]] trait that can be used to
    * create new instances of this actor implementation.
    */
  final val behavior: Factory = (streamBuilder: RadioStreamBuilder,
                                 handleFactory: RadioStreamHandle.Factory,
                                 scheduler: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
                                 cacheTime: FiniteDuration,
                                 bufferSize: Int) => {
    val state = RadioStreamHandleState(
      streamBuilder = streamBuilder,
      handleFactory = handleFactory,
      scheduler = scheduler,
      cacheTime = cacheTime,
      bufferSize = bufferSize,
      cache = Map.empty
    )
    handle(state)
  }

  /**
    * The message handler function for this actor.
    *
    * @param state the current state of the actor
    * @return the behavior of the next state
    */
  private def handle(state: RadioStreamHandleState): Behavior[RadioStreamHandleCommand] =
    Behaviors.receive {
      case (context, ReleaseStreamHandle(source, streamHandle, optLastMetadata)) =>
        context.log.info("Putting stream actor for source '{}' into cache.", source)
        streamHandle.detach()
        val entry = CacheData(streamHandle, optLastMetadata)
        val nextState = state.copy(cache = state.cache + (source -> entry))
        val invocation = ScheduledInvocationActor.typedInvocationCommand(state.cacheTime,
          context.self, CacheTimeEnd(source))
        state.scheduler ! invocation
        handle(nextState)

      case (context, GetStreamHandle(params, replyTo)) =>
        state.cache.get(params.streamSource) match
          case Some(value) =>
            context.log.info("Reusing stream actor for source '{}' from cache.", params.streamSource)
            val (response, nextState) = reuseHandleFromCache(state, params.streamSource, value)
            replyTo ! response
            handle(nextState)
          case None =>
            answerRequestForNewHandle(context, state, params, replyTo)

      case (context, CacheTimeEnd(source)) =>
        state.cache.get(source) match
          case Some(entry) =>
            context.log.info("Removing stream actor for source '{}' from cache.", source)
            entry.streamHandle.cancelStream()
            val nextState = state.copy(cache = state.cache - source)
            handle(nextState)
          case None => Behaviors.same

      case (context, Stop) =>
        context.log.info("Stopping RadioStreamHandleManagerActor.")
        Behaviors.stopped
    }

  /**
    * Returns a response for a stream handle request and the updated state if
    * the source is contained in the cache.
    *
    * @param state  the current actor state
    * @param source the affected radio source
    * @param data   the cache entry for the source
    * @return the response message and the updated state
    */
  private def reuseHandleFromCache(state: RadioStreamHandleState,
                                   source: RadioSource,
                                   data: CacheData): (GetStreamHandleResponse, RadioStreamHandleState) =
    val response = GetStreamHandleResponse(source, Success(data.streamHandle), data.optMetadata)
    val nextState = state.copy(cache = state.cache - source)
    (response, nextState)

  /**
    * Processes a request for a stream handle that needs to be newly created.
    *
    * @param context the context of this actor
    * @param state   the current actor state
    * @param params  the parameters for the radio stream
    * @param replyTo the actor to send the response to
    * @return the next behavior for this actor
    */
  private def answerRequestForNewHandle(context: ActorContext[RadioStreamHandleCommand],
                                        state: RadioStreamHandleState,
                                        params: GetStreamHandleParameters,
                                        replyTo: ActorRef[GetStreamHandleResponse]):
  Behavior[RadioStreamHandleCommand] =
    given classic.ActorSystem = context.system.classicSystem

    given ExecutionContext = context.executionContext

    context.log.info("Creating new radio stream for source '{}'.", params.streamSource)
    createNewHandle(state, params).onComplete { triedHandle =>
      val response = GetStreamHandleResponse(params.streamSource, triedHandle, None)
      replyTo ! response
    }
    Behaviors.same

  /**
    * Creates a new [[RadioStreamHandle]] for a radio source.
    *
    * @param state  the current actor state
    * @param params the parameters for the radio stream
    * @return a [[Future]] with the newly created handle
    */
  private def createNewHandle(state: RadioStreamHandleState,
                              params: GetStreamHandleParameters)
                             (using classic.ActorSystem): Future[RadioStreamHandle] =
    state.handleFactory.create(
      state.streamBuilder,
      params.streamSource.uri,
      state.bufferSize,
      params.streamName
    )
    