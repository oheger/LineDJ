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

package de.oliver_heger.linedj.utils

import de.oliver_heger.linedj.utils.ChildActorFactory.actorNameFor
import org.apache.pekko.actor.{Actor, ActorRef, Props}

import java.util.concurrent.ConcurrentHashMap

object ChildActorFactory:
  /** A map to generate actor names for actor classes. */
  private val actorClassCounters = new ConcurrentHashMap[String, Integer]()

  /**
    * Generates a unique name for an actor created from the specified ''Props''
    * instance.
    *
    * @param props the creation properties of the actor
    * @return a unique name for this actor
    */
  private def actorNameFor(props: Props): String =
    val clsName = props.actorClass().getSimpleName
    clsName + actorClassCounters.compute(clsName, (_, count) => if count == null then 1 else count + 1)

/**
  * A trait offering a generic method for creating child actors.
  *
  * When testing actors that dynamically create child actors there is often the
  * need to inject specific mock actor references. This trait provides a
  * solution for this problem. It defines a generic method for creating actors
  * (using the same ''Props'' mechanism as regular actor creation). Test classes
  * can override this method to return arbitrary test actor references.
  *
  * This trait is fully functional; it can be directly mixed into actor classes
  * that need to create child actors.
  */
trait ChildActorFactory extends Actor:
  /**
    * Creates a child actor based on the specified ''Props'' object. This
    * implementation uses the actor's context to actually create the child. It
    * generates a unique actor name based on the actor class and a counter that
    * is managed per actor class.
    *
    * @param p the ''Props'' defining the actor to be created
    * @return the ''ActorRef'' to the new child actor
    */
  def createChildActor(p: Props): ActorRef = context.actorOf(p, actorNameFor(p))
