/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.impl

import akka.actor.{Actor, ActorRef, Props}
import de.oliver_heger.linedj.io.CloseHandlerActor.CloseComplete
import de.oliver_heger.linedj.io.{CloseRequest, CloseSupport}
import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.player.engine.impl.PlaybackActor.{AddPlaybackContextFactory, RemovePlaybackContextFactory}
import de.oliver_heger.linedj.player.engine.impl.PlayerFacadeActor.SourceActorCreator
import de.oliver_heger.linedj.utils.ChildActorFactory

import scala.concurrent.duration._

object PlayerFacadeActor {
  /** A delay value that means ''no delay''. */
  final val NoDelay: FiniteDuration = 0.seconds

  /** The key in the map of child actors identifying the source actor. */
  final val KeySourceActor = "sourceActor"

  /**
    * A trait defining the supported target actors for invocations via this
    * facade actor.
    *
    * This actor receives messages that indicate the receiving actor and the
    * actual payload. The payload is then dispatched to the receiver.
    */
  sealed trait TargetActor

  /**
    * A special target representing the playback actor.
    */
  case object TargetPlaybackActor extends TargetActor

  /**
    * A special target to select a receiver actor created by the
    * ''SourceActorCreator'' function. The key of the target actor (which must
    * correspond to the key used in the map returned by the function) has to be
    * provided.
    *
    * @param key the key identifying the target actor
    */
  case class TargetSourceReader(key: String = KeySourceActor) extends TargetActor

  /**
    * Definition of a function that creates the dynamic source actor to be
    * connected to the playback actor.
    *
    * When creating the child actors managed, this actor uses this function to
    * create the source actor. By providing different functions, playback of
    * different sources is possible (e.g. from a playlist or internet radio).
    * In some constellations, the source actor collaborates with multiple other
    * actors; therefore, this function returns a map with new child actors that
    * can be accessed by an alphanumeric key. All actors in this map are
    * managed by the facade actor. The source actor must be stored under the
    * key ''KeySourceActor''.
    */
  type SourceActorCreator = (ChildActorFactory, PlayerConfig) => Map[String, ActorRef]

  /**
    * A message processed by [[PlayerFacadeActor]] telling it to dispatch a
    * message to one of the supported target actors.
    *
    * The message payload and the target are defined as properties of this
    * message class. It is also possible to specify a delay; in this case, the
    * message is not dispatched directly, but only after this delay.
    *
    * @param msg         the message to be dispatched
    * @param targetActor the target actor to be invoked
    * @param delay       a delay for message dispatching
    */
  case class Dispatch(msg: Any, targetActor: TargetActor, delay: FiniteDuration = NoDelay)

  /**
    * A message processed by [[PlayerFacadeActor]] telling it to reset the
    * audio engine.
    *
    * This basically creates a new engine instance, destructing the current
    * actors. All playlist information is wiped out; so a new playlist can be
    * constructed.
    */
  case object ResetEngine

  private class PlayerFacadeActorImpl(config: PlayerConfig, eventActor: ActorRef,
                                      lineWriterActor: ActorRef, sourceCreator: SourceActorCreator)
    extends PlayerFacadeActor(config, eventActor, lineWriterActor, sourceCreator) with ChildActorFactory
      with CloseSupport

  /**
    * Returns a ''Props'' object for creating a new actor instance.
    *
    * @param config          the configuration for the player engine
    * @param eventActor      the event manager actor
    * @param lineWriterActor the line writer actor
    * @param sourceCreator   the function to create the source actor(s)
    * @return the ''Props'' to create a new instance
    */
  def apply(config: PlayerConfig, eventActor: ActorRef, lineWriterActor: ActorRef, sourceCreator: SourceActorCreator):
  Props = Props(classOf[PlayerFacadeActorImpl], config, eventActor, lineWriterActor, sourceCreator)
}

/**
  * An actor implementation acting as a facade for the actors comprising the
  * audio player.
  *
  * The audio player engine is optimized to play a list of songs, one after the
  * other. If there is a sudden change in the playlist, e.g. jumping to
  * another song or going back, all buffers currently filled have to be
  * cleaned, and some background processes may have to be stopped. This is a
  * complex and asynchronous process. In fact, it is easier to shutdown the
  * current instance of the engine (i.e. the actors implementing the
  * functionality of the audio engine) and setup a new one. As this is an
  * asynchronous action, too, care has to be taken that no commands get lost
  * that arrive in the time the engine is recreated.
  *
  * This actor is responsible for hiding a restart of the audio engine from
  * the outside. It creates and wraps the actors implementing the engine. When
  * a specific reset command arrives the actors are closed and stopped. Then a
  * set of new actors is created. Messages that arrive in this time frame are
  * queued and processed when the actors are up again.
  *
  * As some use cases of audio players require the execution of actions after a
  * delay, this actor manages a [[DelayActor]]. It exposes the functionality of
  * this actor by accepting [[DelayActor.Propagate]] messages and passing them
  * to this actor. So this functionality is available to clients as well
  * without having to bother with managing their own delay actor instance.
  *
  * Note that this actor does not take ownership of the actors passed to it. So
  * clients are responsible of stopping them. (This is normally handled
  * automatically by the actor creation factory used by a client component.)
  *
  * @param config          the configuration for the player engine
  * @param eventActor      the event manager actor
  * @param lineWriterActor the line writer actor
  * @param sourceCreator   the function to create the source actor(s)
  */
class PlayerFacadeActor(config: PlayerConfig, eventActor: ActorRef, lineWriterActor: ActorRef,
                        sourceCreator: SourceActorCreator)
  extends Actor {
  this: ChildActorFactory with CloseSupport =>

  import PlayerFacadeActor._

  /** The actor for delayed invocations. */
  private var delayActor: ActorRef = _

  /**
    * A map storing the source reader actor(s) returned by the source actor
    * creator function.
    */
  private var sourceReaderActors: Map[String, ActorRef] = _

  /** The current playback actor. */
  private var playbackActor: ActorRef = _

  /** A buffer for messages that arrive during an engine reset. */
  private var messagesDuringReset = List.empty[Dispatch]

  /**
    * Stores the current set of playback context factories. They have to be
    * passed to newly created playback actors.
    */
  private var playbackContextFactories = List.empty[AddPlaybackContextFactory]

  /** Flag whether this actor has been closed. */
  private var closed = false

  override def preStart(): Unit = {
    delayActor = createChildActor(DelayActor())
    createDynamicChildren()
  }

  override def receive: Receive = {
    case d: Dispatch if isCloseRequestInProgress =>
      messagesDuringReset = d :: messagesDuringReset

    case d: Dispatch if !isCloseRequestInProgress =>
      dispatchMessage(d)

    case p: DelayActor.Propagate =>
      delayActor ! p

    case addMsg: AddPlaybackContextFactory =>
      playbackActor ! addMsg
      playbackContextFactories = addMsg :: playbackContextFactories

    case removeMsg: RemovePlaybackContextFactory =>
      playbackActor ! removeMsg
      playbackContextFactories =
        playbackContextFactories filterNot (_.factory == removeMsg.factory)

    case ResetEngine =>
      messagesDuringReset = List.empty
      if (!isCloseRequestInProgress) {
        triggerCloseRequest(self)
      }

    case CloseRequest =>
      closed = true
      triggerCloseRequest(sender())

    case CloseComplete =>
      stopDynamicChildren()
      onCloseComplete()
      if (!closed) {
        createDynamicChildren()
        messagesDuringReset.reverse.foreach(dispatchMessage)
      }
  }

  /**
    * Initiates a close operation by closing all the child actors managed by
    * this instance. When this is done, a confirmation is sent to the target
    * specified.
    *
    * @param target the target to send a confirmation to
    */
  private def triggerCloseRequest(target: ActorRef): Unit = {
    val actorsToClose = List(delayActor, playbackActor) ++ sourceReaderActors.values
    onCloseRequest(self, actorsToClose, target, this)
  }

  /**
    * Handles a dispatch message.
    *
    * @param d the message to be handled
    */
  private def dispatchMessage(d: Dispatch): Unit = {
    delayActor ! DelayActor.Propagate(d.msg, fetchTargetActor(d.targetActor), d.delay)
  }

  /**
    * Determines the actor referenced by the specified target.
    *
    * @param t the target
    * @return the corresponding actor
    */
  private def fetchTargetActor(t: TargetActor): ActorRef =
    t match {
      case TargetSourceReader(key) => sourceReaderActors(key)
      case TargetPlaybackActor => playbackActor
    }

  /**
    * Creates the dynamic children of this actor. These are child actors that
    * have to be replaced when the engine is reset.
    */
  private def createDynamicChildren(): Unit = {
    sourceReaderActors = sourceCreator(this, config)
    playbackActor = createChildActor(PlaybackActor(config, sourceReaderActors(KeySourceActor), lineWriterActor,
      eventActor))
    playbackContextFactories foreach (playbackActor ! _)
  }

  /**
    * Stops the child actors that are replaced on a reset of the engine.
    */
  private def stopDynamicChildren(): Unit = {
    sourceReaderActors.values foreach context.stop
    context stop playbackActor
  }
}
