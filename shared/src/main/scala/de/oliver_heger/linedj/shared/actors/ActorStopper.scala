/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.shared.actors

import org.apache.pekko.actor as classics
import org.apache.pekko.actor.PoisonPill
import org.apache.pekko.actor.typed.ActorRef

object ActorStopper:
  /**
    * Returns a default [[ActorStopper]] for a classics actor. This stopper
    * sends a poison pill to the actor.
    *
    * @param a the actor to be stopped
    * @return the [[ActorStopper]] for this actor
    */
  def classicActorStopper(a: classics.ActorRef): ActorStopper =
    () => a ! PoisonPill

  /**
    * Returns an [[ActorStopper]] for a typed actor that sends a specific
    * stop command to this actor.
    *
    * @param a           the actor to be stopped
    * @param stopCommand the stop command for this actor
    * @tparam C the command type supported by this actor
    * @return the [[ActorStopper]] for this actor
    */
  def typedActorStopper[C](a: ActorRef[C], stopCommand: C): ActorStopper =
    () => a ! stopCommand
end ActorStopper

/**
  * A trait abstracting over stopping a specific actor.
  *
  * This trait is used to support automatic lifecycle management of actors. It
  * allows stopping actors automatically that have been created on behalf of a
  * component when this component terminates. While for classic actors a
  * default mechanism exists to stop them, this is not the case for typed
  * actors; here typically a proprietary message needs to be sent to the actor.
  * Since this trait is rather generic, all these cases can be modeled.
  */
trait ActorStopper:
  /**
    * Stops the actor associated with this instance.
    */
  def stop(): Unit
