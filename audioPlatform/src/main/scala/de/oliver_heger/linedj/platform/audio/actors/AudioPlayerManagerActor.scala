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

package de.oliver_heger.linedj.platform.audio.actors

import akka.actor.typed.Behavior
import de.oliver_heger.linedj.platform.audio.actors.PlayerManagerActor.{PlayerCreationFunc, PlayerManagementCommand}
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.comm.ServiceDependencies.{RegisterService, ServiceDependency, UnregisterService}
import de.oliver_heger.linedj.player.engine.facade.PlayerControl
import de.oliver_heger.linedj.player.engine.{PlaybackContextFactory, PlayerEvent}

import scala.concurrent.{ExecutionContext, Future}

/**
  * An actor implementation that manages the asynchronous creation and
  * initialization of an audio player instance.
  *
  * The lifecycle of an audio player is rather tricky with regards to
  * threading: [[PlaybackContextFactory]] instances can arrive at any time on
  * the OSGi thread; the creation of the player itself is an asynchronous
  * operation, and when required services go away, the player has to be closed
  * again (even if its creation may not yet be complete). This actor
  * implementation handles these aspects; by making use of the actor model,
  * messing with threads manually can be avoided.
  */
object AudioPlayerManagerActor {
  /**
    * Type definition of a function that can create an
    * [[AudioPlayerController]] instance asynchronously.
    */
  type ControllerCreationFunc = () => Future[AudioPlayerController]

  /** The dependency for the controller registration. */
  private val ControllerDependency = ServiceDependency("lineDJ.audioPlayerController")

  /**
    * An internal data class to represent the state of the managed audio player.
    *
    * @param controller             the [[AudioPlayerController]]
    * @param messageBusRegistration the message bus registration ID
    */
  private case class AudioPlayerState(controller: AudioPlayerController,
                                      messageBusRegistration: Int)

  /**
    * Returns the behavior of an actor instance to manage an audio player.
    *
    * @param messageBus the central message bus
    * @param creator    the function to create the audio player controller
    * @param ec         the execution context
    * @return the behavior to create an actor instance
    */
  def apply(messageBus: MessageBus)(creator: ControllerCreationFunc)
           (implicit ec: ExecutionContext): Behavior[PlayerManagementCommand] = {
    val stateCreator: PlayerCreationFunc[AudioPlayerState] = () => {
      creator() map (ctrl => AudioPlayerState(ctrl, 0))
    }

    val manager = new PlayerManagerActor[AudioPlayerState, PlayerEvent] {
      override protected def getPlayer(state: AudioPlayerState): PlayerControl[PlayerEvent] =
        state.controller.player

      override protected def onInit(state: AudioPlayerState): AudioPlayerState = {
        val regID = registerController(messageBus, state.controller)
        state.copy(messageBusRegistration = regID)
      }

      override protected def onClose(state: AudioPlayerState): Unit = {
        messageBus removeListener state.messageBusRegistration
        messageBus publish UnregisterService(ControllerDependency)
      }
    }
    manager.behavior(messageBus)(stateCreator)
  }

  /**
    * Performs all necessary steps to register the given controller as a
    * service of the audio platform.
    *
    * @param messageBus the message bus
    * @param controller the controller
    * @return the message bus listener ID
    */
  private def registerController(messageBus: MessageBus, controller: AudioPlayerController): Int = {
    messageBus publish RegisterService(ControllerDependency)
    messageBus registerListener controller.receive
  }
}
