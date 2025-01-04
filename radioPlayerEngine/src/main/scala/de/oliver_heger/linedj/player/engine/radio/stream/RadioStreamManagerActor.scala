/*
 * Copyright 2015-2025 The Developers Team.
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

import de.oliver_heger.linedj.io.CloseSupportTyped
import de.oliver_heger.linedj.player.engine.actors.ScheduledInvocationActor
import de.oliver_heger.linedj.player.engine.radio._
import de.oliver_heger.linedj.player.engine.{AudioSource, PlayerConfig}
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.{actor => classic}

import scala.concurrent.duration.FiniteDuration

/**
  * A module implementing an actor that manages [[RadioStreamActor]] instances.
  *
  * The purpose of this actor is to use the same actor instances for playing
  * audio data that has been used before when doing metadata checks. Obviously,
  * it can happen that checks for metadata exclusions succeed, but after
  * opening a new connection (when creating a new [[RadioStreamActor]]), the
  * radio source plays again forbidden data. (In the concrete case,
  * advertisements were played when connecting anew to the radio stream.) To
  * prevent this, this actor acts as a cache for radio stream actor instances
  * that have actually been closed. It keeps them open for a while until it
  * finally closes them. If the same radio source is opened again during this
  * time, the same stream actor can be reused.
  *
  * To enable this caching mechanism, [[RadioStreamActor]] instances must be
  * requested from this actor. In addition, instead of closing them, they have
  * to be passed to this actor, so that they can be put into the temporary
  * cache.
  */
object RadioStreamManagerActor:
  /**
    * A data class collecting the parameters required to create a new instance
    * of [[RadioStreamActor]]. An instance must be provided when requesting a
    * stream actor.
    *
    * @param streamSource   the radio source to be played
    * @param sourceListener reference to the function that is invoked with an
    *                       audio source when the final audio stream is
    *                       available
    * @param eventActor     the actor to publish radio events
    */
  case class StreamActorParameters(streamSource: RadioSource,
                                   sourceListener: RadioStreamActor.SourceListener,
                                   eventActor: ActorRef[RadioEvent])

  /**
    * A data class defining the response for a stream actor request. The object
    * mainly contains the reference to the stream actor, which was either newly
    * created or obtained from the cache.
    *
    * @param source      the radio source to be played
    * @param streamActor the [[RadioStreamActor]] reference
    */
  case class StreamActorResponse(source: RadioSource, streamActor: classic.ActorRef)

  /**
    * The base trait for the commands processed by this actor implementation.
    */
  sealed trait RadioStreamManagerCommand

  /**
    * A command to request a [[RadioStreamActor]] instance for a specific radio
    * source to be used by a classic actor.
    *
    * @param params  the parameters for the stream actor
    * @param replyTo the actor to send the response to
    */
  case class GetStreamActorClassic(params: StreamActorParameters,
                                   replyTo: classic.ActorRef) extends RadioStreamManagerCommand

  /**
    * A command to request a [[RadioStreamActor]] instance for a specific radio
    * source that supports a typed actor client.
    *
    * @param params  the parameters for the stream actor
    * @param replyTo the actor to send the response to
    */
  case class GetStreamActor(params: StreamActorParameters,
                            replyTo: ActorRef[StreamActorResponse]) extends RadioStreamManagerCommand

  /**
    * A command that passes a [[RadioStreamActor]] for a specific radio source
    * to this manager actor which is no longer used. The stream actor is put
    * into the cache for the configured cache time. If it is not requested
    * again within this time, it is closed. Together with the actor, some
    * additional data has to be provided that is needed when reusing it from
    * the cache. So the resolved audio source is expected that has to be passed
    * to the source listener. Optionally, the latest known metadata for this
    * source can be specified. This is passed to the event listener actor when
    * the actor becomes active again, since there is no guarantee that an
    * update of metadata happens sometime soon.
    *
    * @param source         the radio source
    * @param streamActor    the [[RadioStreamActor]] to release
    * @param resolvedSource the resolved audio source; this needs to be passed
    *                       to the source listener when the actor is reused
    */
  case class ReleaseStreamActor(source: RadioSource,
                                streamActor: classic.ActorRef,
                                resolvedSource: AudioSource,
                                optLastMetadata: Option[CurrentMetadata] = None) extends RadioStreamManagerCommand

  /**
    * A command telling this actor to stop itself. Before terminating, the
    * actor tries to gracefully all stream actors that are currently contained
    * in the cache.
    */
  case object Stop extends RadioStreamManagerCommand

  /**
    * An internal command telling this actor that the stream actor for a
    * specific radio source should now be removed from the cache if it is still
    * contained there.
    *
    * @param source the radio source affected
    */
  private case class CacheTimeEnd(source: RadioSource) extends RadioStreamManagerCommand

  /**
    * An internal command that is used by this actor to notify itself when a
    * set of stream actors has answered a close request. With this mechanism,
    * an actor instance makes sure that it does not stop itself before all
    * managed stream actors are gracefully closed.
    *
    * @param actors the actors that have been closed
    */
  private case class StreamActorsClosed(actors: Set[classic.ActorRef]) extends RadioStreamManagerCommand

  /**
    * A data class collecting the information that needs to be stored in the
    * cache for stream actors.
    *
    * @param streamActor    the stream actor reference
    * @param resolvedSource the resolved audio source for this stream
    * @param optMetadata    an option with the last known metadata
    */
  private case class CacheData(streamActor: classic.ActorRef,
                               resolvedSource: AudioSource,
                               optMetadata: Option[CurrentMetadata])

  /**
    * A data class representing the state of this actor.
    *
    * @param config        the audio player config
    * @param streamBuilder the object to create radio streams
    * @param scheduler     the scheduled invocation actor
    * @param cacheTime     the time stream actors remain in the cache before
    *                      they are finally closed
    * @param cache         the map with the cache of stream actors
    * @param closing       a set with stream actors for which a close ack is
    *                      pending
    */
  private case class RadioStreamState(config: PlayerConfig,
                                      streamBuilder: RadioStreamBuilder,
                                      scheduler: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
                                      cacheTime: FiniteDuration,
                                      cache: Map[RadioSource, CacheData],
                                      closing: Set[classic.ActorRef])

  /**
    * A factory trait allowing the creation of new instances of this actor
    * implementation.
    */
  trait Factory:
    /**
      * Returns the ''Behavior'' for creating new actor instances.
      *
      * @param config        the audio player config
      * @param streamBuilder the object to create radio streams
      * @param scheduler     the scheduled invocation actor
      * @param cacheTime     the time stream actors remain in the cache before
      *                      they are finally closed
      * @return the ''Behavior'' to create a new instance
      */
    def apply(config: PlayerConfig,
              streamBuilder: RadioStreamBuilder,
              scheduler: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
              cacheTime: FiniteDuration): Behavior[RadioStreamManagerCommand]

  /**
    * A default implementation of the [[Factory]] trait that can be used to
    * create new instances of this actor implementation.
    */
  val behavior: Factory = (config: PlayerConfig,
                           streamBuilder: RadioStreamBuilder,
                           scheduler: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
                           cacheTime: FiniteDuration) => {
    val state = RadioStreamState(config, streamBuilder, scheduler, cacheTime, Map.empty, Set.empty)
    handle(state)
  }

  /**
    * The event handling function for this actor implementation.
    *
    * @param state the current actor state
    * @return the next behavior
    */
  private def handle(state: RadioStreamState): Behavior[RadioStreamManagerCommand] =
    Behaviors.receive:
      case (context, GetStreamActorClassic(params, replyTo)) =>
        val nextState = replayWithNewOrCachedStreamActor(context, params, state)(replyTo.!)
        handle(nextState)

      case (context, GetStreamActor(params, replyTo)) =>
        val nextState = replayWithNewOrCachedStreamActor(context, params, state)(replyTo.!)
        handle(nextState)

      case (context, ReleaseStreamActor(source, streamActor, resolvedSource, optMeta)) =>
        context.log.info("Putting stream actor for source '{}' into cache.", source)
        streamActor ! RadioStreamActor.UpdateEventActor(None)
        val entry = CacheData(streamActor, resolvedSource, optMeta)
        val nextState = state.copy(cache = state.cache + (source -> entry))
        val invocation = ScheduledInvocationActor.typedInvocationCommand(state.cacheTime,
          context.self, CacheTimeEnd(source))
        state.scheduler ! invocation
        handle(nextState)

      case (context, CacheTimeEnd(source)) =>
        state.cache.get(source) match
          case Some(entry) =>
            context.log.info("Removing stream actor for source '{}' from cache.", source)
            val closedMsg = StreamActorsClosed(Set(entry.streamActor))
            CloseSupportTyped.triggerClose(context, context.self, closedMsg, closedMsg.actors)
            val nextState = state.copy(cache = state.cache - source, closing = state.closing + entry.streamActor)
            handle(nextState)
          case None =>
            Behaviors.same

      case (context, StreamActorsClosed(actors)) =>
        handle(handleActorsClosed(context, state, actors))

      case (context, Stop) if state.closing.isEmpty && state.cache.isEmpty =>
        context.log.info("Stopping RadioStreamManagerActor.")
        Behaviors.stopped

      case (context, Stop) =>
        val closedMsg = StreamActorsClosed(state.cache.values.map(_.streamActor).toSet)
        CloseSupportTyped.triggerClose(context, context.self, closedMsg, closedMsg.actors)
        val nextState = state.copy(cache = Map.empty, closing = state.closing ++ closedMsg.actors)
        context.log.info("Received Stop command. Waiting for {} actors to be closed.", nextState.closing.size)
        closing(nextState)

  /**
    * A handler function that becomes active when this actor received a
    * [[Stop]] command and still some stream actors need to be closed. The
    * function waits for all pending ''CloseAck'' messages before it stops this
    * actor.
    *
    * @param state the current state
    * @return the updated behavior
    */
  private def closing(state: RadioStreamState): Behavior[RadioStreamManagerCommand] =
    Behaviors.receivePartial:
      case (context, StreamActorsClosed(actors)) =>
        val nextState = handleActorsClosed(context, state, actors)
        if nextState.closing.isEmpty then Behaviors.stopped
        else closing(nextState)

  /**
    * Obtains a [[RadioStreamActor]] for the given parameters either from the
    * cache or creates a new instance. Creates a response object and passes it
    * to the provided reply function. Returns the updated state. This function
    * makes sure that the source listener is invoked after the response is sent
    * to the client actor.
    *
    * @param context the actor context
    * @param params  the parameters for the stream actor
    * @param state   the current state
    * @param reply   the function to send the reply
    * @return the updated state
    */
  private def replayWithNewOrCachedStreamActor(context: ActorContext[RadioStreamManagerCommand],
                                               params: StreamActorParameters,
                                               state: RadioStreamState)
                                              (reply: StreamActorResponse => Unit): RadioStreamState =
    state.cache.get(params.streamSource) match
      case Some(entry) =>
        context.log.info("Reusing stream actor for source '{}' from cache.", params.streamSource)
        entry.streamActor ! RadioStreamActor.UpdateEventActor(Some(params.eventActor))
        reply(StreamActorResponse(params.streamSource, entry.streamActor))
        params.sourceListener(entry.resolvedSource, entry.streamActor)
        val metadata = entry.optMetadata getOrElse MetadataNotSupported
        val event = RadioMetadataEvent(params.streamSource, metadata)
        params.eventActor ! event
        state.copy(cache = state.cache - params.streamSource)

      case None =>
        val props = RadioStreamActor(state.config,
          params.streamSource,
          params.sourceListener,
          params.eventActor,
          state.streamBuilder)
        reply(StreamActorResponse(params.streamSource, context.actorOf(props)))
        state

  /**
    * Handles a message about closed stream actors by updating the state.
    *
    * @param context the actor context
    * @param state   the current state
    * @param actors  the actors that have been closed
    * @return the updated state
    */
  private def handleActorsClosed(context: ActorContext[RadioStreamManagerCommand],
                                 state: RadioStreamState,
                                 actors: Set[classic.ActorRef]): RadioStreamState =
    val nextState = state.copy(closing = state.closing -- actors)
    if nextState.closing.isEmpty then
      context.log.info("All expected CloseAck messages have been received.")
    else
      context.log.info("Received CloseAck. Still {} actors pending.", nextState.closing.size)
    nextState
