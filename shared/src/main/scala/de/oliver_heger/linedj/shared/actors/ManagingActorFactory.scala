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

package de.oliver_heger.linedj.shared.actors

import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}

object ManagingActorFactory:
  /**
    * Returns a new [[ManagingActorFactory]] that delegates to the provided
    * [[ActorFactory]] for creating new actors and uses the given
    * [[ActorManagement]] instance for managing them.
    *
    * @param actorManagement the [[ActorManagement]] object
    * @param factory         the factory for creating new actors
    * @return the new managing actor factory
    */
  def newManagingActorFactory(actorManagement: ActorManagement)(using factory: ActorFactory): ManagingActorFactory =
    new ManagingActorFactory:
      override val management: ActorManagement = actorManagement

      override def actorSystem: classic.ActorSystem = factory.actorSystem

      override def createClassicActor(props: classic.Props,
                                      name: String,
                                      optStopCommand: Option[Any]): classic.ActorRef =
        val actor = factory.createClassicActor(props, name)

        optStopCommand match
          case Some(stopCommand) =>
            val stopper: ActorStopper = () => actor ! stopCommand
            actorManagement.registerActor(name, stopper, Some(actor))
          case None =>
            actorManagement.registerActor(name, actor)

        actor

      override def createTypedActor[T](behavior: Behavior[T],
                                       name: String,
                                       props: Props,
                                       optStopCommand: Option[T]): ActorRef[T] =
        val ref = factory.createTypedActor(behavior, name, props)
        optStopCommand foreach : command =>
          val stopper = ActorStopper.typedActorStopper(ref, command)
          actorManagement.registerActor(name, stopper)
        ref

  /**
    * Returns a new [[ManagingActorFactory]] instance that uses the provided
    * [[ActorFactory]] for creating new actors. For the management of actors,
    * it creates a new [[ActorManagement]] object.
    *
    * @param factory the factory for creating new actors
    * @return the new managing actor factory
    */
  def newDefaultManagingActorFactory(using factory: ActorFactory): ManagingActorFactory =
    val actorManagement = new ActorManagement {}
    newManagingActorFactory(actorManagement)
end ManagingActorFactory

/**
  * A trait that combines the creation of actors with functionality to manage
  * them, e.g. store them and stop them.
  *
  * The management functionality is provided by an [[ActorManagement]] object
  * associated with an instance. Newly created actors are registered at this
  * object.
  *
  * Note that this trait shares the same limitations with regard to typed 
  * actors as the [[ActorManagement]] trait. For instance, newly created typed
  * actors can only be stopped if an [[ActorStopper]] is provided for them.
  */
trait ManagingActorFactory extends ActorFactory:
  /**
    * The [[ActorManagement]] object used by this factory. The most relevant
    * functionality is available via the [[ManagingActorFactory]] interface. 
    * Clients that need extended access to the managed actors can use this
    * property.
    */
  val management: ActorManagement

  export management.{getActor, managedActorNames, stopActors}
