/*
 * Copyright 2015-2024 The Developers Team.
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

import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.actors.*
import de.oliver_heger.linedj.player.engine.actors.PlayerFacadeActor.TargetPlaybackActor
import de.oliver_heger.linedj.player.engine.{ActorCreator, PlaybackContextFactory, PlayerConfig}
import org.apache.pekko.actor as classics
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorRef, Props, Scheduler}
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout

import scala.collection.immutable.Seq
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

object PlayerControl:
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
                            actorName: String = LineWriterActorName): ActorRef[LineWriterActor.LineWriterCommand] =
    config.actorCreator.createActor(LineWriterActor(), actorName, None, createLineWriterActorProps(config))

  /**
    * Creates actors for managing event listeners and publishing events. This
    * function creates the event manager actor and then asks it for the
    * dedicated actor to publish events. Since this means an asynchronous
    * request, result is a ''Future''.
    *
    * @param creator the object to create actors
    * @param name    the name of the event manager actor
    * @param ec      the execution context
    * @tparam E the event type
    * @return a tuple with the event manager actor and the publisher actor
    */
  def createEventManagerActorWithPublisher[E](creator: ActorCreator, name: String)
                                             (implicit system: ActorSystem, ec: ExecutionContext, t: ClassTag[E]):
  Future[(ActorRef[EventManagerActor.EventManagerCommand[E]], ActorRef[E])] =
    val eventManagerActor = creator.createActor(EventManagerActor[E](), name, Some(EventManagerActor.Stop[E]()))

    implicit val askTimeout: Timeout = Timeout(10.seconds)
    import org.apache.pekko.actor.typed.scaladsl.adapter.*
    implicit val scheduler: Scheduler = system.toTyped.scheduler
    eventManagerActor.ask[EventManagerActor.PublisherReference[E]] { ref =>
      EventManagerActor.GetPublisher(ref)
    } map { publisherRef => (eventManagerActor, publisherRef.publisher) }

  /**
    * Creates the [[ScheduledInvocationActor]] used by this player.
    *
    * @param creator the object to create actors
    * @param name    the name of the scheduler actor
    * @return the reference to the newly created actor
    */
  def createSchedulerActor(creator: ActorCreator, name: String):
  ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand] =
    creator.createActor(ScheduledInvocationActor(), name, Some(ScheduledInvocationActor.Stop))

  /**
    * Creates the [[PlaybackContextFactoryActor]] used by this player.
    *
    * @param creator the object to create actors
    * @param name    the name of the factory actor
    * @return the reference to the newly created actor
    */
  def createPlaybackContextFactoryActor(creator: ActorCreator, name: String):
  ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand] =
    creator.createActor(PlaybackContextFactoryActor(), name, Some(PlaybackContextFactoryActor.Stop))

  /**
    * Creates the properties for the line writer actor to be used by this audio
    * player.
    *
    * @param config the ''PlayerConfig''
    * @return creation properties for the line writer actor
    */
  private[facade] def createLineWriterActorProps(config: PlayerConfig): Props =
    config applyBlockingDispatcher Props.empty

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
trait PlayerControl[E]:

  import PlayerControl.*

  /** The actor for managing event listeners. */
  protected val eventManagerActor: ActorRef[EventManagerActor.EventManagerCommand[E]]

  /** The actor managing the playback context factories. */
  protected val playbackContextFactoryActor: ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand]

  /** The actor handling scheduled invocation. */
  protected val scheduledInvocationActor: ActorRef[ScheduledInvocationActor.ActorInvocationCommand]

  /**
    * The facade actor for the player engine. This actor is not really used by
    * this base trait, but it is needed for a concrete implementation.
    * Therefore, it is stored here, and it is especially taken into account
    * when closing the player.
    */
  protected val playerFacadeActor: classics.ActorRef

  /**
    * Adds the specified ''PlaybackContextFactory'' to this audio player.
    * Before audio files can be played, corresponding factories supporting
    * this audio format have to be added.
    *
    * @param factory the ''PlaybackContextFactory'' to be added
    */
  def addPlaybackContextFactory(factory: PlaybackContextFactory): Unit =
    playbackContextFactoryActor ! PlaybackContextFactoryActor.AddPlaybackContextFactory(factory)

  /**
    * Removes the specified ''PlaybackContextFactory'' from this audio player.
    * Audio files handled by this factory can no longer be played.
    *
    * @param factory the ''PlaybackContextFactory'' to be removed
    */
  def removePlaybackContextFactory(factory: PlaybackContextFactory): Unit =
    playbackContextFactoryActor ! PlaybackContextFactoryActor.RemovePlaybackContextFactory(factory)

  /**
    * Starts audio playback. Provided that sufficient audio data has been
    * loaded, playback will start. Optionally, it is possible to specify a
    * delay. The start of playback will then be scheduled after that delay.
    *
    * @param delay a delay for starting playback
    */
  def startPlayback(delay: FiniteDuration = DelayActor.NoDelay): Unit =
    handleInvocation(startPlaybackInvocation, delay)

  /**
    * Stops audio playback. Playback is paused and can be continued by calling
    * ''startPlayback()''. Optionally, it is possible to specify a delay when
    * playback should stop.
    *
    * @param delay a delay for stopping playback
    */
  def stopPlayback(delay: FiniteDuration = DelayActor.NoDelay): Unit =
    handleInvocation(stopPlaybackInvocation, delay)

  /**
    * Adds the given actor as listener for events generated by this audio
    * player.
    *
    * @param listener the event listener actor
    */
  def addEventListener(listener: ActorRef[E]): Unit =
    eventManagerActor ! EventManagerActor.RegisterListener(listener)

  /**
    * Removes an event listener actor from this audio player.
    *
    * @param listener the event listener actor to remove
    */
  def removeEventListener(listener: ActorRef[E]): Unit =
    eventManagerActor ! EventManagerActor.RemoveListener(listener)

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
  def close()(implicit ec: ExecutionContext, timeout: Timeout): Future[Seq[CloseAck]] =
    val futureAck = playerFacadeActor ? CloseRequest
    futureAck.mapTo[CloseAck] map { List(_) }

  /**
    * Returns a [[ScheduledInvocationActor.ActorInvocation]] for starting
    * playback. This base trait uses this object to start playback directly or
    * after a delay.
    *
    * @return the invocation to start playback
    */
  protected def startPlaybackInvocation: ScheduledInvocationActor.ActorInvocation

  /**
    * Returns a [[ScheduledInvocationActor.ActorInvocation]] for stopping
    * playback. This base trait uses this object to stop playback directly or
    * after a delay.
    *
    * @return the invocation to stop playback
    */
  protected def stopPlaybackInvocation: ScheduledInvocationActor.ActorInvocation

  /**
    * Invokes the playback actor with the specified message and delay. This
    * method is used to handle some basic functionality related to playback
    * control.
    *
    * @param msg   the message to be sent to the playback actor
    * @param delay a delay for this request
    */
  protected def invokePlaybackActor(msg: Any, delay: FiniteDuration): Unit =
    invokeFacadeActor(msg, TargetPlaybackActor, delay)

  /**
    * Helper method to send a message to the facade actor, converting the data
    * passed in to a ''Dispatch'' message interpreted by this actor.
    *
    * @param msg    the message to be sent
    * @param target the receiver of the message
    * @param delay  a delay
    */
  protected def invokeFacadeActor(msg: Any, target: PlayerFacadeActor.TargetActor,
                                  delay: FiniteDuration = PlayerControl.NoDelay): Unit =
    playerFacadeActor ! PlayerFacadeActor.Dispatch(msg, target, delay)

  /**
    * Handles the given invocation either by directly executing it or passing
    * it to the scheduler actor if there is a delay.
    *
    * @param invocation the invocation
    * @param delay      the delay
    */
  private def handleInvocation(invocation: ScheduledInvocationActor.ActorInvocation, delay: FiniteDuration): Unit =
    if delay > NoDelay then
      scheduledInvocationActor ! ScheduledInvocationActor.ActorInvocationCommand(delay, invocation)
    else
      invocation.send()
