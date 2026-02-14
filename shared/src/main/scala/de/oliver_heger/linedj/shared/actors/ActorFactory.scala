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

import com.github.cloudfiles.core.http.factory.Spawner
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props, typed}

object ActorFactory:
  /**
    * Provides a default [[ActorFactory]] that uses the given actor system.
    * This default factory only supports actor creation; it ignores the 
    * [[ActorStopper]] parameters. Life-cycle management for actors may be
    * implemented on top of it.
    *
    * @param system the actor system
    * @return the default [[ActorFactory]] based on this actor system
    */
  given defaultActorFactory(using system: ActorSystem): ActorFactory =
  new ActorFactory:
    /** The object for creating typed actors. */
    private lazy val spawner: Spawner = system

    /** The underlying actor system that is used to create actors. */
    override val actorSystem: ActorSystem = system

    override def createClassicActor(props: Props, name: String, optStopCommand: Option[Any]): ActorRef =
      system.actorOf(props, name)

    override def createTypedActor[T](behavior: Behavior[T],
                                     name: String,
                                     props: typed.Props,
                                     optStopCommand: Option[T]): typed.ActorRef[T] =
      spawner.spawn(behavior, Option(name), props)
end ActorFactory

/**
  * A trait supporting the creation of actors.
  *
  * This trait defines operations for creating classic and typed actors. It
  * allows abstracting from the concrete creation process and by that improves
  * testability of clients that use actors.
  *
  * The functions to create actors also support parameters to stop the actors
  * when they are no longer needed. This enables concrete implementations to
  * offer life-cycle management; although not all implementations necessarily
  * have to provide this.
  */
trait ActorFactory:
  /**
    * Returns the underlying actor system that is used to create actors.
    *
    * @return the [[ActorSystem]] used by this instance
    */
  def actorSystem: ActorSystem

  /**
    * Creates a classic actor based on the provided ''Props''.
    *
    * @param props          the ''Props'' for the new actor
    * @param name           the name of the actor
    * @param optStopCommand an optional command to stop this actor
    * @return the reference to the newly created actor
    */
  def createClassicActor(props: Props, name: String, optStopCommand: Option[Any] = None): ActorRef

  /**
    * Creates a typed actor based on the provided ''Behavior''.
    *
    * @param behavior       the ''Behavior'' of the new actor
    * @param name           the name of the actor
    * @param props          additional properties for the new actor
    * @param optStopCommand an optional command to stop this actor
    * @tparam T the type of messages processed by the actor
    * @return the reference to the newly created actor
    */
  def createTypedActor[T](behavior: Behavior[T],
                          name: String,
                          props: typed.Props = typed.Props.empty,
                          optStopCommand: Option[T] = None): typed.ActorRef[T]
