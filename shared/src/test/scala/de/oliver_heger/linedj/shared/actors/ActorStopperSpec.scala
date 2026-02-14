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

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.{Actor, ActorSystem, Props}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

/**
  * Test class for [[ActorStopper]].
  */
class ActorStopperSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike with BeforeAndAfterAll
  with Matchers:
  def this() = this(ActorSystem("ActorStopperSpec"))

  /** The test kit for testing typed actors. */
  private val typedTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  "ActorStopper" should "create a default stopper for classic actors" in :
    val actorToStop = system.actorOf(Props(new Actor:
      override def receive: Receive = Actor.emptyBehavior
    ))

    val stopper = ActorStopper.classicActorStopper(actorToStop)
    stopper.stop()

    val probe = TestProbe()
    probe.watch(actorToStop)
    probe.expectTerminated(actorToStop)

  it should "create a stopper for typed actors" in :
    val StopCommand = "stop!"
    val probeActorToStop = typedTestKit.createTestProbe[String]()

    val stopper = ActorStopper.typedActorStopper(probeActorToStop.ref, StopCommand)
    stopper.stop()

    probeActorToStop.expectMessage(StopCommand)
    