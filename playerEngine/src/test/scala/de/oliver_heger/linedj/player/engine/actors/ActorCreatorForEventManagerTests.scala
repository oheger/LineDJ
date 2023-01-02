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

package de.oliver_heger.linedj.player.engine.actors

import akka.actor.testkit.typed.scaladsl
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.{ActorSystem, Props}
import akka.testkit.TestProbe
import akka.{actor => classics}
import de.oliver_heger.linedj.player.engine.ActorCreator
import de.oliver_heger.linedj.player.engine.actors.ActorCreatorForEventManagerTests.{ActorCheckFunc, ClassicActorCheckFunc, EmptyCheckFunc, EmptyClassicActorCheckFunc}
import org.scalatest.matchers.should.Matchers

import scala.language.existentials

/**
  * A module providing functionality required by tests related to
  * [[EventManagerActor]].
  *
  * The module provides a configurable [[ActorCreator]] implementation that can
  * already handle the actors needed by players to support event generation.
  */
object ActorCreatorForEventManagerTests {
  /**
    * Alias for a function that checks the creation parameters of a typed actor
    * and returns a corresponding (stub) actor reference. The function is
    * passed the affected actor's behavior and its optional stop command. The
    * resulting ''PartialFunction'' can then doe arbitrary checks based the
    * actor name.
    */
  type ActorCheckFunc = (Behavior[_], Option[_]) => PartialFunction[String, ActorRef[_]]

  /**
    * Alias for a function that checks the creation parameters of a classic
    * actor and returns a corresponding (stub) actor reference.
    */
  type ClassicActorCheckFunc = Props => PartialFunction[String, classics.ActorRef]

  /** A check function that does not contain any checks. */
  final val EmptyCheckFunc: ActorCheckFunc = (_, _) => PartialFunction.empty

  /** A check function for classic actors that does not contain any checks. */
  final val EmptyClassicActorCheckFunc: ClassicActorCheckFunc = _ => PartialFunction.empty
}

/**
  * A special implementation of [[ActorCreator]] that supports creating test
  * probes for event manager actors.
  *
  * An instance can be configured with partial functions that test the actor's
  * creation parameter and return a stub actor reference. When an actor of a
  * specific name is to be created, this implementation invokes the partial
  * function and returns the actor reference returned by it.
  *
  * The class already provides check functions for the event manager actors.
  *
  * @param testKit             the test kit for typed actors
  * @param eventActorName      the name of the event manager actor
  * @param customChecks        a map with checks for typed actors
  * @param customClassicChecks a map with checks for classic actors
  * @param system              the actor system
  * @tparam EVENT the type of events for the event manager actor
  */
class ActorCreatorForEventManagerTests[EVENT](testKit: ActorTestKit,
                                              eventActorName: String,
                                              customChecks: ActorCheckFunc = EmptyCheckFunc,
                                              customClassicChecks: ClassicActorCheckFunc = EmptyClassicActorCheckFunc)
                                             (implicit system: ActorSystem)
  extends ActorCreator {
  this: Matchers =>

  /** Test probe for the legacy event manager actor. */
  val probeEventActorOld: TestProbe = TestProbe()

  /** Test probe for the event manager actor. */
  val probeEventActor: scaladsl.TestProbe[EventManagerActor.EventManagerCommand[EVENT]] =
    testKit.createTestProbe[EventManagerActor.EventManagerCommand[EVENT]]()

  /** Test probe for the event publisher actor. */
  val probePublisherActor: scaladsl.TestProbe[EVENT] = testKit.createTestProbe[EVENT]()

  /**
    * A stub behavior simulating the event manager actor that can handle the
    * [[EventManagerActor.GetPublisher]] message to return the corresponding
    * publisher test probe.
    */
  private val mockEventManagerBehavior =
    Behaviors.receiveMessagePartial[EventManagerActor.EventManagerCommand[EVENT]] {
      case EventManagerActor.GetPublisher(client) =>
        client ! EventManagerActor.PublisherReference(probePublisherActor.ref)
        Behaviors.same
    }

  /**
    * Check function for the event manager actor. The function returns a stub
    * behavior that can be queried for the publisher actor and is additionally
    * monitored by a test probe.
    */
  private val eventManagerCheck: ActorCheckFunc = (_, optStopCommand) => {
    case `eventActorName` =>
      optStopCommand should be(Some(EventManagerActor.Stop[EVENT]()))
      testKit.spawn(Behaviors.monitor(probeEventActor.ref, mockEventManagerBehavior))
  }

  override def createActor[T](behavior: Behavior[T], name: String, optStopCommand: Option[T]): ActorRef[T] = {
    val checkFunc = customChecks(behavior, optStopCommand) orElse eventManagerCheck(behavior, optStopCommand)
    checkFunc.isDefinedAt(name) shouldBe true
    val ref = checkFunc(name)
    ref.asInstanceOf[ActorRef[T]]
  }

  override def createActor(props: Props, name: String): classics.ActorRef = {
    val checkFunc = customClassicChecks(props)
    checkFunc.isDefinedAt(name) shouldBe true
    checkFunc(name)
  }
}
