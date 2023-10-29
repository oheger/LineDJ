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

package de.oliver_heger.linedj.platform.comm

import org.apache.pekko.actor.Actor.Receive

/**
 * A trait defining an object that needs to listen on the message bus.
 *
 * Such objects are typically declared via the dependency injection framework.
 * When the application starts up the registration at the message bus is done.
 * The trait just defines a single function for obtaining the ''Receive''
 * function which handles events published to the message bus.
 */
trait MessageBusListener {
  /**
   * Returns the function for handling messages published on the message bus.
   * @return the message handling function
   */
  def receive: Receive
}
