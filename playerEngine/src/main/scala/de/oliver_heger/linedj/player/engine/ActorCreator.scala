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

package de.oliver_heger.linedj.player.engine

import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.apache.pekko.{actor => classic}

/**
  * Definition of a trait that allows the player engine to create actors
  * without having to know details about the concrete setup with an actor
  * system, etc. The functions defined here are invoked in order to create the
  * several helper actors making up the audio player engine implementation.
  *
  * Note that the player engine does not stop actors; this is in the
  * responsibility of the creator component. So, an implementation may want to
  * keep track on newly created actors, so that they can be stopped later.
  */
trait ActorCreator:
  /**
    * Creates a typed actor for the given behavior with the specified name.
    * Since there is no default way to stop typed actors, it is possible to
    * specify a command that can be used for this purpose.
    *
    * @param behavior       the behavior for the new actor
    * @param name           the name to use for this actor
    * @param optStopCommand an optional command to stop the actor
    * @param props          additional properties for the new actor
    * @tparam T the type of messages processed by the actor
    * @return the reference to the newly created actor
    */
  def createActor[T](behavior: Behavior[T],
                     name: String,
                     optStopCommand: Option[T],
                     props: Props = Props.empty): ActorRef[T]

  /**
    * Creates a classic actor based on the given ''Props'' with the specified
    * name. Optionally, a command to stop this actor can be provided. If this
    * is not specified, the actor is stopped by sending it a ''PoisonPill''.
    *
    * @param props          the ''Props'' for the new actor instance
    * @param name           the name to use for this actor
    * @param optStopCommand an optional command to stop the actor
    * @return the reference to the newly created actor
    */
  def createClassicActor(props: classic.Props, name: String, optStopCommand: Option[Any] = None): classic.ActorRef
