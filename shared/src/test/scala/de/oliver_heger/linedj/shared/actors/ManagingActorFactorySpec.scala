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

package de.oliver_heger.linedj.shared.actors

import org.apache.pekko.actor as classic
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}

object ManagingActorFactorySpec:
  /** A special string sent by the classic test actor when it was stopped. */
  private val StopNotification = "stopped"

  /**
    * A command class for a test typed actor.
    */
  private enum TestActorCommand:
    case Trigger
    case Stop

  /**
    * Creates the behavior of a new test actor instance. The test actor uses
    * a queue to send notifications that it is still alive when it receives a 
    * ''Trigger'' command. This function returns the new actor instance and the
    * queue that it uses for this purpose.
    *
    * @return the test instance and the trigger queue
    */
  private def testActorBehavior: (Behavior[TestActorCommand], BlockingQueue[String]) =
    val queue = new LinkedBlockingQueue[String]

    def handleCommand(count: Int): Behavior[TestActorCommand] =
      Behaviors.receiveMessage:
        case TestActorCommand.Trigger =>
          val nextCount = count + 1
          queue.offer(triggerMessage(nextCount))
          handleCommand(nextCount)

        case TestActorCommand.Stop => Behaviors.stopped

    (handleCommand(0), queue)

  /**
    * Test implementation of a classic actor that provides the same
    * functionality as the typed actor.
    *
    * @param queue the queue for sending responses to trigger messages
    */
  private class TestActorImpl(queue: LinkedBlockingQueue[String]) extends classic.Actor:
    private var count: Int = 0

    override def receive: Receive =
      case TestActorCommand.Trigger =>
        count += 1
        queue.offer(triggerMessage(count))

      case TestActorCommand.Stop =>
        queue.offer(StopNotification)
        context.stop(self)
  end TestActorImpl

  /**
    * Returns a tuple with a ''Props'' object to create a classic test actor
    * instance and the queue to receive trigger responses from the test actor.
    *
    * @return the test actor instance and the trigger queue
    */
  private def testActorProps: (classic.Props, BlockingQueue[String]) =
    val queue = new LinkedBlockingQueue[String]
    (classic.Props(new TestActorImpl(queue)), queue)

  /**
    * Generates a message for the test actor based on the given index.
    *
    * @param idx the index of the message
    * @return the test message with this index
    */
  private def triggerMessage(idx: Int): String = s"Ping_$idx"
end ManagingActorFactorySpec

/**
  * Test class for [[ManagingActorFactory]].
  */
class ManagingActorFactorySpec(testSystem: classic.ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(classic.ActorSystem("ManagingActorFactorySpec"))

  /** The test kit for testing typed actors. */
  private val typedTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import ManagingActorFactorySpec.*

  /**
    * Reads the next value from the given queue that has been written by a test
    * actor. This is used to test whether the actor is in an expected state.
    *
    * @param queue the queue
    * @return the next value read from the queue
    */
  private def readTriggerQueue(queue: BlockingQueue[String]): String =
    val count = queue.poll(3, TimeUnit.SECONDS)
    count should not be null
    count

  "A ManagingActoryFactory" should "create a typed actor" in :
    val ActorName = "MyTestTypedActor"
    val (behavior, queue) = testActorBehavior
    val factory = ManagingActorFactory.newDefaultManagingActorFactory

    val actor = factory.createTypedActor(behavior, ActorName)
    actor ! TestActorCommand.Trigger
    readTriggerQueue(queue) should be(triggerMessage(1))

    factory.managedActorNames shouldBe empty
    factory.stopActors()
    actor ! TestActorCommand.Trigger
    readTriggerQueue(queue) should be(triggerMessage(2))

  it should "create a typed actor and register a stopper for a stop command" in :
    val ActorName = "MyTestTypedActorWithStopCommand"
    val (behavior, queue) = testActorBehavior
    val factory = ManagingActorFactory.newDefaultManagingActorFactory

    val actor = factory.createTypedActor(behavior, ActorName, optStopCommand = Some(TestActorCommand.Stop))
    actor ! TestActorCommand.Trigger
    readTriggerQueue(queue) should be(triggerMessage(1))

    factory.managedActorNames should contain only ActorName
    val watcherProbe = typedTestKit.createDeadLetterProbe()
    factory.stopActors()
    watcherProbe.expectTerminated(actor)

  it should "create a classic actor" in :
    val ActorName = "MyTestClassicActor"
    val (props, queue) = testActorProps
    val factory = ManagingActorFactory.newDefaultManagingActorFactory

    val actor = factory.createClassicActor(props, ActorName)
    actor ! TestActorCommand.Trigger
    readTriggerQueue(queue) should be(triggerMessage(1))

    factory.management.managedActorNames should contain only ActorName
    factory.management.getActor(ActorName) should be(actor)

    factory.stopActors()
    val watcherProbe = TestProbe()
    watcherProbe.watch(actor)
    watcherProbe.expectTerminated(actor)

  it should "create a classic actor with a custom stop command" in :
    val ActorName = "MyTestClassicActorWithStopCommand"
    val (props, queue) = testActorProps
    val factory = ManagingActorFactory.newDefaultManagingActorFactory

    val actor = factory.createClassicActor(props, ActorName, optStopCommand = Some(TestActorCommand.Stop))
    actor ! TestActorCommand.Trigger
    readTriggerQueue(queue) should be(triggerMessage(1))

    factory.stopActors()
    val watcherProbe = TestProbe()
    watcherProbe.watch(actor)
    watcherProbe.expectTerminated(actor)
    readTriggerQueue(queue) should be(StopNotification)

  it should "use a provided ActorManagement instance" in :
    val ActorName = "ManagedClassicActor"
    val management = mock[ActorManagement]
    val (props, queue) = testActorProps
    val factory = ManagingActorFactory.newManagingActorFactory(management)

    val actor = factory.createClassicActor(props, ActorName)

    verify(management).registerActor(ActorName, actor)

  it should "return the correct actor system" in :
    val mockSystem = mock[classic.ActorSystem]
    val mockBaseFactory = mock[ActorFactory]
    when(mockBaseFactory.actorSystem).thenReturn(mockSystem)

    val factory = ManagingActorFactory.newDefaultManagingActorFactory(using mockBaseFactory)

    factory.actorSystem should be(mockSystem)
    