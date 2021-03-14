/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.platform.comm

import akka.actor.{ActorRef, ActorSystem, Props}

/**
 * A class for creating actors.
 *
 * This class holds an instance of an ''ActorSystem'' and provides a method for
 * creating an actor in this system. Some controller classes need to create
 * specific actors; therefore, it makes sense to have this functionality on a
 * central place. This also simplifies testing.
 *
 * @param actorSystem the current ''ActorSystem''
 */
class ActorFactory(val actorSystem: ActorSystem) {
  def createActor(props: Props, name: String): ActorRef =
    actorSystem.actorOf(props, name)
}
