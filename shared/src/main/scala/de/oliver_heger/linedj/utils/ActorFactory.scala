/*
 * Copyright 2015-2024 The Developers Team.
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

import com.github.cloudfiles.core.http.factory.Spawner
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props, typed}

/**
  * A class for creating actors.
  *
  * This class holds an instance of an ''ActorSystem'' and provides methods for
  * creating an actor in this system. Some controller classes need to create
  * specific actors; therefore, it makes sense to have this functionality on a
  * central place. This also simplifies testing.
  *
  * @param actorSystem the current ''ActorSystem''
  */
class ActorFactory(val actorSystem: ActorSystem):
  /** The object for creating typed actors. */
  private lazy val spawner: Spawner = actorSystem

  /**
    * Creates a classic actor based on the provided ''Props''.
    *
    * @param props the ''Props'' for the new actor
    * @param name  the name of the actor
    * @return the reference to the newly created actor
    */
  def createActor(props: Props, name: String): ActorRef =
    actorSystem.actorOf(props, name)

  /**
    * Creates a typed actor based on the provided ''Behavior''.
    *
    * @param behavior the ''Behavior'' of the new actor
    * @param name     the name of the actor
    * @param props    additional properties for the new actor
    * @tparam T the type of messages processed by the actor
    * @return the reference to the newly created actor
    */
  def createActor[T](behavior: Behavior[T], name: String, props: typed.Props = typed.Props.empty): typed.ActorRef[T] =
    spawner.spawn(behavior, Option(name), props)
