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

package de.oliver_heger.linedj

import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, Props}
import org.apache.pekko.testkit.TestProbe

import scala.language.implicitConversions

/**
  * A module providing support for ''TestProbe''s that can be stopped.
  *
  * The Akka testkit obviously does not like it if test probes are stopped.
  * This can cause strange behavior in test cases executed later, even up to
  * failures of the test actor system. Nevertheless, when testing actors that
  * use death watch for actors they collaborate with, it is useful to have a
  * ''TestProbe'' in place to simulate the collaborator and to stop it to check
  * whether watching is handled correctly.
  *
  * This module solves this problem by introducing a class that mainly combines
  * a ''TestProbe'' with a regular actor. The actor simply forwards all
  * messages it receives to the ''TestProbe''. Its reference is passed to the
  * actor under test. It can also be stopped without affecting the
  * ''TestProbe''.
  */
object StoppableTestProbe {
  /**
    * Creates a new instance of ''StoppableTestProbe''.
    *
    * @param system the actor system
    * @return the ''StoppableTestProbe'' instance
    */
  def apply()(implicit system: ActorSystem): StoppableTestProbe = {
    val probe = TestProbe()
    val forwarder = system.actorOf(Props(new ForwardingActor(probe)))
    new StoppableTestProbe(forwarder, probe)
  }

  /**
    * An implicit conversion from a ''StoppableTestProbe'' to an ''ActorRef''.
    * This function allows treating the probe object as a regular actor.
    *
    * @param probe the ''StoppableTestProbe''
    * @return the ''ActorRef'' of this probe
    */
  implicit def toActorRef(probe: StoppableTestProbe): ActorRef = probe.ref

  /**
    * An implicit conversion from a ''StoppableTestProbe'' to a ''TestProbe''.
    * This function allows applying the typical ''expectXXX()'' functions to an
    * instance without having to reference the probe part.
    *
    * @param probe the ''StoppableTestProbe''
    * @return the ''TestProbe'' of this probe
    */
  implicit def toTestProbe(probe: StoppableTestProbe): TestProbe = probe.probe

  /**
    * An internal actor class that simply forwards all messages to the given
    * ''TestProbe''.
    *
    * @param probe the ''TestProbe'' to forward messages to
    */
  private class ForwardingActor(probe: TestProbe) extends Actor {
    override def receive: Receive = {
      case msg => probe.ref forward msg
    }
  }
}

/**
  * A class representing a ''TestProbe'' that can be stopped safely.
  *
  * @param ref   the ''ActorRef'' to be used for the outside
  * @param probe the ''TestProbe''
  */
class StoppableTestProbe(val ref: ActorRef,
                         val probe: TestProbe) {
  /**
    * Stops the actor of this probe.
    *
    * @param system the actor system
    */
  def stop()(implicit system: ActorSystem): Unit = {
    system stop ref
  }
}
