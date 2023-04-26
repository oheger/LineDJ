/*
 * Copyright 2015-2023 The Developers Team.
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

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.{actor => classic}
import de.oliver_heger.linedj.io.CloseSupportTyped
import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.player.engine.actors.ScheduledInvocationActor
import de.oliver_heger.linedj.player.engine.radio.{RadioEvent, RadioSource}

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
object RadioStreamManagerActor {
  /**
    * A data class collecting the parameters required to create a new instance
    * of [[RadioStreamActor]]. An instance must be provided when requesting a
    * stream actor.
    *
    * @param streamSource   the radio source to be played
    * @param sourceListener reference to an actor that is sent an audio source
    *                       message when the final audio stream is available
    * @param eventActor     the actor to publish radio events
    */
  case class StreamActorParameters(streamSource: RadioSource,
                                   sourceListener: classic.ActorRef,
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
    * again within this time, it is closed.
    *
    * @param source      the radio source
    * @param streamActor the [[RadioStreamActor]] to release
    */
  case class ReleaseStreamActor(source: RadioSource, streamActor: classic.ActorRef) extends RadioStreamManagerCommand

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
                                      cache: Map[RadioSource, classic.ActorRef],
                                      closing: Set[classic.ActorRef])

  /**
    * A factory trait allowing the creation of new instances of this actor
    * implementation.
    */
  trait Factory {
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
  }

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
    Behaviors.receive {
      case (context, GetStreamActorClassic(params, replyTo)) =>
        val (streamActor, nextState) = getOrCreateStreamActor(context, params, state)
        replyTo ! StreamActorResponse(params.streamSource, streamActor)
        handle(nextState)

      case (context, GetStreamActor(params, replyTo)) =>
        val (streamActor, nextState) = getOrCreateStreamActor(context, params, state)
        replyTo ! StreamActorResponse(params.streamSource, streamActor)
        handle(nextState)

      case (context, ReleaseStreamActor(source, streamActor)) =>
        context.log.info("Putting stream actor for source '{}' into cache.", source)
        streamActor ! RadioStreamActor.UpdateEventActor(None)
        val nextState = state.copy(cache = state.cache + (source -> streamActor))
        val invocation = ScheduledInvocationActor.typedInvocationCommand(state.cacheTime,
          context.self, CacheTimeEnd(source))
        state.scheduler ! invocation
        handle(nextState)

      case (context, CacheTimeEnd(source)) =>
        state.cache.get(source) match {
          case Some(value) =>
            context.log.info("Removing stream actor for source '{}' from cache.", source)
            val closedMsg = StreamActorsClosed(Set(value))
            CloseSupportTyped.triggerClose(context, context.self, closedMsg, closedMsg.actors)
            val nextState = state.copy(cache = state.cache - source, closing = state.closing + value)
            handle(nextState)
          case None =>
            Behaviors.same
        }

      case (context, StreamActorsClosed(actors)) =>
        handle(handleActorsClosed(context, state, actors))

      case (context, Stop) if state.closing.isEmpty && state.cache.isEmpty =>
        context.log.info("Stopping RadioStreamManagerActor.")
        Behaviors.stopped

      case (context, Stop) =>
        val closedMsg = StreamActorsClosed(state.cache.values.toSet)
        CloseSupportTyped.triggerClose(context, context.self, closedMsg, closedMsg.actors)
        val nextState = state.copy(cache = Map.empty, closing = state.closing ++ closedMsg.actors)
        context.log.info("Received Stop command. Waiting for {} actors to be closed.", nextState.closing.size)
        closing(nextState)
    }

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
    Behaviors.receivePartial {
      case (context, StreamActorsClosed(actors)) =>
        val nextState = handleActorsClosed(context, state, actors)
        if (nextState.closing.isEmpty) Behaviors.stopped
        else closing(nextState)
    }

  /**
    * Obtains a [[RadioStreamActor]] for the given parameters either from the
    * cache or creates a new instance. Returns the actor reference and the
    * updated state.
    *
    * @param context the actor context
    * @param params  the parameters for the stream actor
    * @param state   the current state
    * @return the stream actor reference and the updated state
    */
  private def getOrCreateStreamActor(context: ActorContext[RadioStreamManagerCommand],
                                     params: StreamActorParameters,
                                     state: RadioStreamState): (classic.ActorRef, RadioStreamState) =
    state.cache.get(params.streamSource) match {
      case Some(actor) =>
        context.log.info("Reusing stream actor for source '{}' from cache.", params.streamSource)
        actor ! RadioStreamActor.UpdateEventActor(Some(params.eventActor))
        (actor, state.copy(cache = state.cache - params.streamSource))
      case None =>
        val props = RadioStreamActor(state.config,
          params.streamSource,
          params.sourceListener,
          params.eventActor,
          state.streamBuilder)
        (context.actorOf(props), state)
    }

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
                                 actors: Set[classic.ActorRef]): RadioStreamState = {
    val nextState = state.copy(closing = state.closing -- actors)
    if (nextState.closing.isEmpty)
      context.log.info("All expected CloseAck messages have been received.")
    else
      context.log.info("Received CloseAck. Still {} actors pending.", nextState.closing.size)
    nextState
  }
}
