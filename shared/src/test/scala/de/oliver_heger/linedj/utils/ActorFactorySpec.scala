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

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.{Actor, ActorSystem, Props}
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean

/**
  * Test class for ''ActorFactory''.
  */
class ActorFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("ActorFactorySpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  "An ActorFactory" should "allow creating a new classic actor" in:
    val props = Props[DummyActor]()
    val Name = "MyTestActor"

    val factory = new ActorFactory(system)
    val ref = factory.createActor(props, Name)

    ref.path.name should endWith(Name)
    val message = TestMessage()
    ref ! message
    awaitCond(message.flag.get())

  it should "allow creating a typed actor" in:
    val Name = "MyTypedTestActor"
    val behavior = Behaviors.receiveMessage[TestMessage]:
      case TestMessage(flag) =>
        flag.set(true)
        Behaviors.same

    val factory = new ActorFactory(system)
    val ref = factory.createActor(behavior, Name)

    ref.path.name should endWith(Name)
    val message = TestMessage()
    ref ! message
    awaitCond(message.flag.get())

/**
  * A message class used to test whether actors have been created successfully.
  *
  * @param flag a flag to be set by an actor
  */
case class TestMessage(flag: AtomicBoolean = new AtomicBoolean)

class DummyActor extends Actor:
  override def receive: Receive =
    case TestMessage(flag) =>
      flag.set(true)
