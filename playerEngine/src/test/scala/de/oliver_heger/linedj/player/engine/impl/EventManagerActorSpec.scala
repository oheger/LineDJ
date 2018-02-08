/*
 * Copyright 2015-2018 The Developers Team.
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

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}

import akka.Done
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.scaladsl.Sink
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.linedj.player.engine.{AudioSource, AudioSourceFinishedEvent,
AudioSourceStartedEvent, PlayerEvent}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.annotation.tailrec
import scala.concurrent.Future

object EventManagerActorSpec {
  /** A timeout when accessing a queue. */
  private val QueueTimeout = 3

  /** A test audio source. */
  private val Source = AudioSource("testSource", 100, 0, 0)

  /** A list with test events. */
  private val Events = List(AudioSourceStartedEvent(Source), AudioSourceFinishedEvent(Source),
    AudioSourceStartedEvent(Source.copy(uri = "someOtherSource")))

  /**
    * Creates a sink that stores all its data in the given queue.
    *
    * @param queue the queue
    * @return the test sink
    */
  private def queuingSink(queue: BlockingQueue[PlayerEvent]): Sink[PlayerEvent, Future[Done]] =
    Sink.foreach(e => queue.offer(e))

  /**
    * Publishes a sequence of events to a test actor.
    *
    * @param actor  the test actor
    * @param events the sequence of events to be published
    */
  private def publishEvents(actor: ActorRef, events: PlayerEvent*): Unit = {
    events foreach (e => actor ! e)
  }
}

/**
  * Test class for ''EventManagerActor''.
  */
class EventManagerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("EventManagerActorSpec"))

  import EventManagerActorSpec._

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Tests that the expected events are received.
    *
    * @param expected the expected events
    * @param queue    the queue to which received events are added
    */
  private def expectQueuedEvents(expected: List[PlayerEvent], queue: BlockingQueue[PlayerEvent]):
  Unit = {
    @tailrec def go(expList: List[PlayerEvent]): Unit = {
      expList match {
        case h :: t =>
          val event = queue.poll(QueueTimeout, TimeUnit.SECONDS)
          event should be(h)
          go(t)
        case Nil =>
          queue.poll(200, TimeUnit.MILLISECONDS) should be(null)
      }
    }
    go(expected)
  }

  "An EventManagerActor" should "propagate events to a registered sink" in {
    val queue = new LinkedBlockingQueue[PlayerEvent]
    val sink = queuingSink(queue)
    val actor = system.actorOf(Props[EventManagerActor])

    actor ! EventManagerActor.RegisterSink(1, sink)
    publishEvents(actor, Events: _*)
    expectQueuedEvents(Events, queue)
  }

  it should "allow removing registered sinks" in {
    val queue1 = new LinkedBlockingQueue[PlayerEvent]
    val queue2 = new LinkedBlockingQueue[PlayerEvent]
    val sink = queuingSink(queue1)
    val otherSink = queuingSink(queue2)
    val events = Events splitAt 1
    val actor = system.actorOf(Props[EventManagerActor])

    actor ! EventManagerActor.RegisterSink(1, sink)
    actor ! EventManagerActor.RegisterSink(2, otherSink)
    publishEvents(actor, events._1: _*)
    actor ! EventManagerActor.RemoveSink(1)
    publishEvents(actor, events._2: _*)
    expectQueuedEvents(events._1, queue1)
    expectQueuedEvents(Events, queue2)
  }
}
