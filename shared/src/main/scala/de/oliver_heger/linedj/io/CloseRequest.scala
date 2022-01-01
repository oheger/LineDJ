/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.io

import akka.actor.ActorRef

/**
 * A message requesting an actor to close all resources it is currently using.
 *
 * This message is used abort currently running operations in a graceful way.
 * It is also sent to actors when the system goes down to implement a correct
 * shutdown process. Actors supporting this message should react by sending
 * a corresponding acknowledge message. This allows the system to find out
 * when all active actors have finished their activities and are ready to go
 * down.
 */
case object CloseRequest

/**
 * A message sent back by an actor to acknowledge that it has been closed.
 *
 * This message is typically sent as a reaction on a [[CloseRequest]] message.
 * The component initiation the close process can then be sure that the actor
 * finished its processing and can be shutdown safely.
 *
 * @param actor a reference to the actor that has been closed
 */
case class CloseAck(actor: ActorRef)
