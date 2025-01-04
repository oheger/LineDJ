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

package de.oliver_heger.linedj.io

import de.oliver_heger.linedj.io.CloseSupportTypedSpec.{CloseTestResult, closeTestActor}
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.{actor => classic}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

object CloseSupportTypedSpec:
  /**
    * A trait defining the commands of the close test actor.
    */
  sealed trait CloseTestCommand

  /**
    * A command indicating a completed close operation.
    */
  case object CloseComplete extends CloseTestCommand

  /**
    * A confirmation message sent by the close test actor when receiving the
    * message that the close operation is complete. The message contains the
    * children of the test actor. This can be used to check whether temporary
    * actors involved in the close operation have all terminated.
    *
    * @param remainingChildren the remaining children of the test actor
    */
  case class CloseTestResult(remainingChildren: Iterable[ActorRef[Nothing]])

  /**
    * Returns the behavior of an actor to test a close operation. The actor
    * triggers the operation on the given dependencies. When it is successful
    * a notification to the client actor is sent.
    *
    * @param client       the actor monitoring the test
    * @param dependencies the dependencies to be closed
    * @return the behavior for the close test actor
    */
  private def closeTestActor(client: ActorRef[CloseTestResult],
                             dependencies: Iterable[classic.ActorRef]): Behavior[CloseTestCommand] =
    Behaviors.setup { context =>
      CloseSupportTyped.triggerClose(context, context.self, CloseComplete, dependencies)

      Behaviors.receiveMessage:
        case CloseComplete =>
          client ! CloseTestResult(context.children)
          Behaviors.same
    }

/**
  * Test class for [[CloseSupportTyped]].
  */
class CloseSupportTypedSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(classic.ActorSystem("CloseSupportTypedSpec"))

  /** The test kit to test typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()
    TestKit shutdownActorSystem system
    super.afterAll()

  "CloseSupportTyped" should "control a close operation" in:
    val dep1 = TestProbe()
    val dep2 = TestProbe()
    val client = testKit.createTestProbe[CloseTestResult]()

    testKit.spawn(closeTestActor(client.ref, List(dep1.ref, dep2.ref)))

    dep1.expectMsg(CloseRequest)
    dep1.reply(CloseAck(dep1.ref))
    dep2.expectMsg(CloseRequest)
    dep2.reply(CloseAck(dep2.ref))
    client.expectMessageType[CloseTestResult]

  it should "not send the completion message before all dependencies are closed" in:
    val dep1 = TestProbe()
    val dep2 = TestProbe()
    val client = testKit.createTestProbe[CloseTestResult]()

    testKit.spawn(closeTestActor(client.ref, List(dep1.ref, dep2.ref)))

    dep1.expectMsg(CloseRequest)
    dep1.reply(CloseAck(dep1.ref))
    client.expectNoMessage(250.millis)

  it should "handle dependencies that died" in:
    val dep1 = TestProbe()
    val dep2 = TestProbe()
    val client = testKit.createTestProbe[CloseTestResult]()

    testKit.spawn(closeTestActor(client.ref, List(dep1.ref, dep2.ref)))

    dep1.expectMsg(CloseRequest)
    dep1.reply(CloseAck(dep1.ref))
    system.stop(dep2.ref)
    client.expectMessageType[CloseTestResult]

  it should "stop the internal child actor controlling the close operation" in:
    val dep = TestProbe()
    val client = testKit.createTestProbe[CloseTestResult]()

    testKit.spawn(closeTestActor(client.ref, List(dep.ref)))
    dep.expectMsg(CloseRequest)
    dep.reply(CloseAck(dep.ref))

    lazy val watcherProbe = testKit.createDeadLetterProbe()
    val result = client.expectMessageType[CloseTestResult]
    result.remainingChildren foreach { child =>
      watcherProbe.expectTerminated(child)
    }
