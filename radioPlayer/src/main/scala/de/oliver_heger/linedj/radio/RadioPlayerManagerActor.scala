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

package de.oliver_heger.linedj.radio

import de.oliver_heger.linedj.platform.audio.actors.PlayerManagerActor
import de.oliver_heger.linedj.platform.audio.actors.PlayerManagerActor.PlayerManagementCommand
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.player.engine.facade.PlayerControl
import de.oliver_heger.linedj.player.engine.radio.RadioEvent
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import org.apache.pekko.actor.typed.Behavior

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * An actor implementation that manages a radio player instance.
  *
  * Based on the [[PlayerManagerActor]] trait, this implementation takes care
  * that a radio player is correctly created asynchronously and initialized. It
  * deals with potential race conditions when adding playback context factories.
  */
object RadioPlayerManagerActor:
  def apply(messageBus: MessageBus)
           (playerCreationFunc: () => Future[RadioPlayer]): Behavior[PlayerManagementCommand] =
    val manager = new PlayerManagerActor[RadioPlayer, RadioEvent]:
      override protected def getPlayer(state: RadioPlayer): PlayerControl[RadioEvent] = state

      override protected def onInit(state: RadioPlayer): RadioPlayer =
        messageBus publish RadioController.RadioPlayerInitialized(Success(state))
        state

      override protected def onInitFailure(cause: Throwable): Unit =
        messageBus publish RadioController.RadioPlayerInitialized(Failure(cause))

      override protected def onClose(state: RadioPlayer): Unit = {}

    manager.behavior(messageBus)(playerCreationFunc)
