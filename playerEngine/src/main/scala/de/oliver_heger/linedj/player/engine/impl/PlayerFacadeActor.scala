/*
 * Copyright 2015-2018 The Developers Team.
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
import de.oliver_heger.linedj.utils.ChildActorFactory

import scala.concurrent.duration._

object PlayerFacadeActor {
  /** A delay value that means ''no delay''. */
  val NoDelay: FiniteDuration = 0.seconds

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
    * A special target representing the source download actor.
    */
  case object TargetDownloadActor extends TargetActor

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
                                      lineWriterActor: ActorRef)
    extends PlayerFacadeActor(config, eventActor, lineWriterActor) with ChildActorFactory
      with CloseSupport

  /**
    * Returns a ''Props'' object for creating a new actor instance.
    *
    * @param config          the configuration for the player engine
    * @param eventActor      the event manager actor
    * @param lineWriterActor the line writer actor
    * @return the ''Props'' to create a new instance
    */
  def apply(config: PlayerConfig, eventActor: ActorRef, lineWriterActor: ActorRef): Props =
    Props(classOf[PlayerFacadeActorImpl], config, eventActor, lineWriterActor)
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
  * @param config          the configuration for the player engine
  * @param eventActor      the event manager actor
  * @param lineWriterActor the line writer actor
  */
class PlayerFacadeActor(config: PlayerConfig, eventActor: ActorRef, lineWriterActor: ActorRef)
  extends Actor {
  this: ChildActorFactory with CloseSupport =>

  import PlayerFacadeActor._

  /** The actor for delayed invocations. */
  private var delayActor: ActorRef = _

  /** The current local buffer actor. */
  private var localBufferActor: ActorRef = _

  /** The current source reader actor. */
  private var sourceReaderActor: ActorRef = _

  /** The current source download actor. */
  private var sourceDownloadActor: ActorRef = _

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
    delayActor = createChildActor(Props[DelayActor])
    createDynamicChildren()
  }

  override def receive: Receive = {
    case d: Dispatch if isCloseRequestInProgress =>
      messagesDuringReset = d :: messagesDuringReset

    case d: Dispatch if !isCloseRequestInProgress =>
      dispatchMessage(d)

    case addMsg: AddPlaybackContextFactory =>
      playbackActor ! addMsg
      playbackContextFactories = addMsg :: playbackContextFactories

    case removeMsg: RemovePlaybackContextFactory =>
      playbackActor ! removeMsg
      playbackContextFactories =
        playbackContextFactories filterNot(_.factory == removeMsg.factory)

    case ResetEngine =>
      messagesDuringReset = List.empty
      if (!isCloseRequestInProgress) {
        onCloseRequest(self, List(delayActor, localBufferActor, sourceReaderActor,
          sourceDownloadActor, playbackActor), self, this)
      }

    case CloseRequest =>
      closed = true
      onCloseRequest(self, List(delayActor, localBufferActor, sourceReaderActor,
        sourceDownloadActor, playbackActor), sender(), this)

    case CloseComplete =>
      stopDynamicChildren()
      onCloseComplete()
      if (!closed) {
        createDynamicChildren()
        messagesDuringReset.reverse.foreach(dispatchMessage)
      }
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
      case TargetDownloadActor => sourceDownloadActor
      case TargetPlaybackActor => playbackActor
    }

  /**
    * Creates the dynamic children of this actor. These are child actors that
    * have to be replaced when the engine is reset.
    */
  private def createDynamicChildren(): Unit = {
    val bufMan = BufferFileManager(config)
    localBufferActor = createChildActor(LocalBufferActor(config, bufMan))
    sourceReaderActor = createChildActor(Props(classOf[SourceReaderActor], localBufferActor))
    sourceDownloadActor = createChildActor(SourceDownloadActor(config, localBufferActor,
      sourceReaderActor))
    playbackActor = createChildActor(PlaybackActor(config, sourceReaderActor, lineWriterActor,
      eventActor))
    playbackContextFactories foreach(playbackActor ! _)
  }

  /**
    * Stops the child actors that are replaced on a reset of the engine.
    */
  private def stopDynamicChildren(): Unit = {
    context stop localBufferActor
    context stop sourceReaderActor
    context stop sourceDownloadActor
    context stop playbackActor
  }
}
