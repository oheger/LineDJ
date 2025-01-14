/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.client.config

import de.oliver_heger.linedj.player.engine.ActorCreator
import de.oliver_heger.linedj.utils.ActorManagement.ActorStopper
import de.oliver_heger.linedj.utils.{ActorFactory, ActorManagement}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.apache.pekko.{actor => classic}

/**
  * A fully functional implementation of [[ActorCreator]] that is based on an
  * [[ActorFactory]] and uses an [[ActorManagement]] object to manage the
  * actors that have been created..
  *
  * This implementation uses the provided actor factory to create new actors.
  * The actors are then also registered at the management instance, so that
  * they can be stopped properly when they are no longer needed.
  *
  * @param actorManagement the [[ActorManagement]] instance
  * @param actorFactory    the [[ActorFactory]] instance
  */
class ManagingActorCreator(val actorFactory: ActorFactory,
                           val actorManagement: ActorManagement) extends ActorCreator:
  /**
    * Creates a typed actor for the given behavior with the specified name.
    * Since there is no default way to stop typed actors, it is possible to
    * specify a command that can be used for this purpose.
    *
    * @param behavior       the behavior for the new actor
    * @param name           the name to use for this actor
    * @param optStopCommand an optional command to stop the actor
    * @tparam T the type of messages processed by the actor
    * @return the reference to the newly created actor
    */
  override def createActor[T](behavior: Behavior[T],
                              name: String,
                              optStopCommand: Option[T],
                              props: Props): ActorRef[T] =
    val ref = actorFactory.createActor(behavior, name, props)
    optStopCommand foreach { command =>
      val stopper: ActorStopper = () => ref ! command
      actorManagement.registerActor(name, stopper)
    }
    ref

  /**
    * Creates a classic actor based on the given ''Props'' with the specified
    * name.
    *
    * @param props the ''Props'' for the new actor instance
    * @param name  the name to use for this actor
    * @return the reference to the newly created actor
    */
  override def createClassicActor(props: classic.Props, name: String, optStopCommand: Option[Any]): classic.ActorRef =
    val actor = actorFactory.createActor(props, name)

    optStopCommand match
      case Some(stopCommand) =>
        val stopper: ActorStopper = () => actor ! stopCommand
        actorManagement.registerActor(name, stopper, Some(actor))
      case None =>
        actorManagement.registerActor(name, actor)

    actor
