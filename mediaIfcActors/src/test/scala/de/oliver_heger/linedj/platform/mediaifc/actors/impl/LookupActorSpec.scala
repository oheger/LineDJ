/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.platform.mediaifc.actors.impl

import java.util.concurrent.{CountDownLatch, SynchronousQueue, TimeUnit}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.linedj.platform.ActorSystemTestHelper
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

/**
 * Test class for ''LookupActor''.
 */
class LookupActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystemTestHelper createActorSystem "RemoteLookupActorSpec")

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A LookupActor" should "detect the monitored actor when it is started" in {
    val ActorName = "monitorStartActor"
    val actorPath = "/user/" + ActorName
    val sequence, nextSequence = mock[DelaySequence]
    val queue = new SynchronousQueue[ActorRef]
    when(sequence.nextDelay).thenAnswer(new Answer[(Int, DelaySequence)] {
      // Create the monitored actor dynamically
      override def answer(invocationOnMock: InvocationOnMock): (Int, DelaySequence) = {
        queue.put(system.actorOf(Props[ActorToBeMonitored], ActorName))
        (1, nextSequence)
      }
    })
    when(nextSequence.nextDelay).thenReturn((1, nextSequence))

    val lookupActor = system.actorOf(Props(classOf[LookupActor], actorPath, testActor,
      sequence))
    val monitoredActor = queue.take()
    expectMsg(LookupActor.RemoteActorAvailable(actorPath, monitoredActor))
    verify(nextSequence).nextDelay
    system stop lookupActor
  }

  it should "detect the death of the monitored actor and start another lookup" in {
    val ActorName = "monitorStopActor"
    val actorPath = "/user/" + ActorName
    val sequence, nextSequence = mock[DelaySequence]
    val latch = new CountDownLatch(3)
    val answer = new Answer[(Int, DelaySequence)] {
      // Countdown latch to indicate that the sequence was queried
      override def answer(invocationOnMock: InvocationOnMock): (Int, DelaySequence) = {
        latch.countDown()
        (1, nextSequence)
      }
    }
    when(sequence.nextDelay).thenAnswer(answer)
    when(nextSequence.nextDelay).thenAnswer(answer)

    val monitoredActor = system.actorOf(Props[ActorToBeMonitored], ActorName)
    val lookupActor = system.actorOf(Props(classOf[LookupActor], actorPath, testActor,
      sequence))
    expectMsg(LookupActor.RemoteActorAvailable(actorPath, monitoredActor))

    system stop monitoredActor
    expectMsg(LookupActor.RemoteActorUnavailable(actorPath))
    // Test reset of delay sequence
    latch.await(5, TimeUnit.SECONDS) shouldBe true
    system stop lookupActor
  }
}

/**
 * A simple, non-functional actor class used within tests.
 */
private class ActorToBeMonitored extends Actor {
  override def receive: Receive = Actor.emptyBehavior
}