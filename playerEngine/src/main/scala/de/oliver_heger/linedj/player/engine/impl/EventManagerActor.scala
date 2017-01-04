/*
 * Copyright 2015-2017 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine.impl

import akka.actor.{Actor, ActorRef}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import de.oliver_heger.linedj.player.engine.PlayerEvent

object EventManagerActor {
  /** The buffer size used by the actorRef sources. */
  private val BufferSize = 8

  /**
    * A message processed by [[EventManagerActor]] telling it to register the
    * specified sink as event listeners. Incoming events will be propagated to
    * this sink. The numeric ID is used to identify the listener registration,
    * so that the listener can be removed later. The caller is responsible to
    * generate unique IDs.
    *
    * @param listenerID the unique ID of the listener
    * @param sink       the sink to be registered
    */
  case class RegisterSink(listenerID: Int, sink: Sink[_, _])

  /**
    * A message processed by [[EventManagerActor]] telling it to remove the
    * sink registration with the specified listener ID. The ID corresponds to
    * the ID used for the registration.
    *
    * @param listenerID the ID of the listener to be removed
    */
  case class RemoveSink(listenerID: Int)
}

/**
  * An actor class responsible for the management of event listeners and event
  * publishing.
  *
  * This actor implements the major part of the functionality related to event
  * generation in the audio player engine. Actors of the player engine that
  * can produce events are passed a reference to this actor. By sending it a
  * ''Publish'' message, an event can be propagated to all listeners currently
  * registered.
  *
  * Event listeners are registered in form of a ''Sink'' object of type
  * [[de.oliver_heger.linedj.player.engine.PlayerEvent]]. Events to be
  * published are passed to all sinks. This makes it possible to use the full
  * functionality of Akka streams to handle events, e.g. by declaring filter or
  * mapping rules.
  */
class EventManagerActor extends Actor {

  import EventManagerActor._

  /** The object for materializing streams. */
  private implicit val materializer = ActorMaterializer()

  /** Stores the registered listeners. */
  private var listeners = Map.empty[Int, ActorRef]

  override def receive: Receive = {
    case RegisterSink(id, sink) =>
      val source = Source.actorRef(BufferSize, OverflowStrategy.dropNew)
      val graph = source.toMat(sink)(Keep.left)
      listeners += id -> graph.run()

    case RemoveSink(id) =>
      listeners = listeners - id

    case e: PlayerEvent =>
      listeners.values foreach (_ ! e)
  }
}
