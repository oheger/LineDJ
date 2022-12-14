/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.facade

import java.util.concurrent.atomic.AtomicInteger
import akka.{actor => classics}
import akka.actor.Props
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.pattern.ask
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.actors.PlayerFacadeActor.TargetPlaybackActor
import de.oliver_heger.linedj.player.engine.{ActorCreator, PlaybackContextFactory, PlayerConfig}
import de.oliver_heger.linedj.player.engine.actors.{DelayActor, EventManagerActor, EventManagerActorOld, LineWriterActor, PlaybackActor, PlayerFacadeActor}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object PlayerControl {
  /**
    * Constant for a delay value that means "no delay". This value can be
    * passed to methods supporting a delayed invocation. It then means that an
    * immediate invocation should take place.
    */
  val NoDelay: FiniteDuration = DelayActor.NoDelay

  /** The default name of the line writer actor. */
  val LineWriterActorName = "lineWriterActor"

  /**
    * Creates the line writer actor for the audio player. This is a bit tricky
    * because this actor is to be deployed on an alternative dispatcher as it
    * executes blocking operations. An alternative dispatcher is only used if
    * one is defined in the configuration.
    *
    * @param config    the ''PlayerConfig''
    * @param actorName an optional actor name (if a different name than the
    *                  standard name is to be used)
    * @return the reference to the line writer actor
    */
  def createLineWriterActor(config: PlayerConfig,
                            actorName: String = LineWriterActorName): classics.ActorRef =
    config.actorCreator.createActor(createLineWriterActorProps(config), actorName)

  /**
    * Creates actors for managing event listeners. This function is mainly
    * used in the interim phase when both typed and untyped event listeners are
    * supported. Therefore, two event manager actors are created and returned.
    * These actors are connected with each other: an event published via the
    * typed actor is also received by the classic actor. That way a step-wise
    * migration to the new event manager implementation can be done.
    *
    * @param creator the object to create actors
    * @param name    the name of the event manager actor
    * @tparam E the event type
    * @return a tuple with the classic and the typed event manager actors
    */
  def createEventManagerActor[E](creator: ActorCreator, name: String):
  (classics.ActorRef, ActorRef[EventManagerActor.EventManagerCommand[E]]) = {
    val eventManagerActorOld = creator.createActor(Props[EventManagerActorOld](), name + "Old")
    val eventManagerActor = creator.createActor(EventManagerActor[E](), name, Some(EventManagerActor.Stop[E]()))

    val forwardingListenerBehavior = Behaviors.receiveMessage[E] {
      msg =>
        eventManagerActorOld ! msg
        Behaviors.same
    }
    val forwardingListener = creator.createActor(forwardingListenerBehavior, "forwardingListener", None)
    eventManagerActor ! EventManagerActor.RegisterListener(forwardingListener)

    (eventManagerActorOld, eventManagerActor)
  }

  /**
    * Creates the properties for the line writer actor to be used by this audio
    * player.
    *
    * @param config the ''PlayerConfig''
    * @return creation properties for the line writer actor
    */
  private[facade] def createLineWriterActorProps(config: PlayerConfig): Props =
    config applyBlockingDispatcher Props[LineWriterActor]()
}

/**
  * A trait describing the common interface of and providing some basic
  * functionality for concrete audio player implementations.
  *
  * This trait assumes that a concrete player makes use of a
  * ''PlayerFacadeActor''. It already implements some common methods for
  * typical interactions with this actor and the ''PlaybackActor'' managed by
  * it. There are also some other helper functions that can be used by player
  * implementations. For instance, there is support for the registration of
  * event listeners and for delayed invocations of operations.
  *
  * A class extending this trait has to provide access to a couple of actors
  * that are referenced by methods of this trait. That way, functionality
  * using these actors can already be implemented while the concrete logic of
  * creating the required actors is left to concrete implementations.
  *
  * @tparam E the type of events supported by this player
  */
trait PlayerControl[E] {

  import PlayerControl._

  /** The legacy actor for generating events. */
  protected val eventManagerActorOld: classics.ActorRef

  /** The actor for managing event listeners. */
  protected val eventManagerActor: ActorRef[EventManagerActor.EventManagerCommand[E]]

  /** The facade actor for the player engine. */
  protected val playerFacadeActor: classics.ActorRef

  /** A counter for generating unique sink registration IDs. */
  private val regIdCounter = new AtomicInteger

  /**
    * Adds the specified ''PlaybackContextFactory'' to this audio player.
    * Before audio files can be played, corresponding factories supporting
    * this audio format have to be added.
    *
    * @param factory the ''PlaybackContextFactory'' to be added
    */
  def addPlaybackContextFactory(factory: PlaybackContextFactory): Unit = {
    playerFacadeActor ! PlaybackActor.AddPlaybackContextFactory(factory)
  }

  /**
    * Removes the specified ''PlaybackContextFactory'' from this audio player.
    * Audio files handled by this factory can no longer be played.
    *
    * @param factory the ''PlaybackContextFactory'' to be removed
    */
  def removePlaybackContextFactory(factory: PlaybackContextFactory): Unit = {
    playerFacadeActor ! PlaybackActor.RemovePlaybackContextFactory(factory)
  }

  /**
    * Starts audio playback. Provided that sufficient audio data has been
    * loaded, playback will start. Optionally, it is possible to specify a
    * delay. The start of playback will then be scheduled after that delay.
    *
    * @param delay a delay for starting playback
    */
  def startPlayback(delay: FiniteDuration = DelayActor.NoDelay): Unit = {
    invokePlaybackActor(PlaybackActor.StartPlayback, delay)
  }

  /**
    * Stops audio playback. Playback is paused and can be continued by calling
    * ''startPlayback()''. Optionally, it is possible to specify a delay when
    * playback should stop.
    *
    * @param delay a delay for stopping playback
    */
  def stopPlayback(delay: FiniteDuration = DelayActor.NoDelay): Unit = {
    invokePlaybackActor(PlaybackActor.StopPlayback, delay)
  }

  /**
    * Skips the current audio source. Playback continues (if enabled) with the
    * next source in the playlist (if any).
    */
  def skipCurrentSource(): Unit = {
    invokePlaybackActor(PlaybackActor.SkipSource, NoDelay)
  }

  /**
    * Registers the specified ''Sink'' as listener for events generated by
    * this audio player. The returned ID can be used to later remove the sink
    * registration again.
    *
    * @param sink the event sink
    * @return a listener registration ID
    */
  def registerEventSink(sink: Sink[_, _]): Int = {
    val regId = regIdCounter.incrementAndGet()
    eventManagerActorOld ! EventManagerActorOld.RegisterSink(regId, sink)
    regId
  }

  /**
    * Removes an event sink registration. The passed in registration ID must be
    * the one returned by the corresponding ''registerEventSink()'' call.
    *
    * @param registrationID the ID of the sink to be removed
    */
  def removeEventSink(registrationID: Int): Unit = {
    eventManagerActorOld ! EventManagerActorOld.RemoveSink(registrationID)
  }

  /**
    * Resets the player engine. This stops playback and clears the current
    * playlist and all audio buffers. This method should be invoked before
    * switching playback to a different source.
    */
  def reset(): Unit = {
    playerFacadeActor ! PlayerFacadeActor.ResetEngine
  }

  /**
    * Closes this player. This is an asynchronous process. It typically
    * requires several actors used by this player to be closed; a safe shutdown
    * is possible only after these actors have confirmed this request. The
    * returned future can be used by the caller to find out when the close
    * operation is completed.
    *
    * Note that it lies in the responsibility of the caller to stop the
    * actors used by this ''PlayerControl''. (The actors have been created via
    * a factory provided by the client; thus they should be destroyed by the
    * client as well.)
    *
    * @param ec      the execution context for the future
    * @param timeout the timeout when waiting for answers
    * @return a ''Future'' for the ''CloseAck'' messages from child actors
    */
  def close()(implicit ec: ExecutionContext, timeout: Timeout): Future[Seq[CloseAck]]

  /**
    * Invokes the playback actor with the specified message and delay. This
    * method is used to handle some basic functionality related to playback
    * control.
    *
    * @param msg   the message to be sent to the playback actor
    * @param delay a delay for this request
    */
  protected def invokePlaybackActor(msg: Any, delay: FiniteDuration): Unit = {
    invokeFacadeActor(msg, TargetPlaybackActor, delay)
  }

  /**
    * Helper method to send a message to the facade actor, converting the data
    * passed in to a ''Dispatch'' message interpreted by this actor.
    *
    * @param msg    the message to be sent
    * @param target the receiver of the message
    * @param delay  a delay
    */
  protected def invokeFacadeActor(msg: Any, target: PlayerFacadeActor.TargetActor,
                                  delay: FiniteDuration = PlayerControl.NoDelay): Unit = {
    playerFacadeActor ! PlayerFacadeActor.Dispatch(msg, target, delay)
  }

  /**
    * Closes the provided actors by sending them a ''CloseRequest'' message and
    * returns a ''Future'' to find out when this request has been answered by
    * all. This method can be used in concrete implementations to achieve a
    * robust close handling. Typically, implementations of ''close()'' will
    * call this method to close all child actors which require close handling.
    * Note that the actors managed by this instance are automatically added to
    * the list of actors to be closed.
    *
    * @param actors  a sequence of actors to be closed
    * @param ec      the execution context for the future
    * @param timeout the timeout when waiting for answers
    * @return a future for the ''CloseAck'' messages received from the actors
    */
  protected def closeActors(actors: Seq[classics.ActorRef])
                           (implicit ec: ExecutionContext, timeout: Timeout):
  Future[Seq[CloseAck]] = {
    val actorsToClose = playerFacadeActor :: actors.toList
    val futureRequests = actorsToClose.map(_ ? CloseRequest)
    Future.sequence(futureRequests).mapTo[Seq[CloseAck]]
  }
}
