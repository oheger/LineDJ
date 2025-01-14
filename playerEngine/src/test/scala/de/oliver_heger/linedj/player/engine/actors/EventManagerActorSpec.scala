/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.actors

import de.oliver_heger.linedj.{ActorTestKitSupport, AsyncTestHelper}
import de.oliver_heger.linedj.player.engine.{AudioSource, AudioSourceStartedEvent, PlaybackProgressEvent, PlayerEvent}
import org.apache.pekko.actor.DeadLetter
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.Timeout
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future
import scala.concurrent.duration.*

object EventManagerActorSpec:
  /** An audio source used by tests. */
  private val TestAudioSource = AudioSource("someSong.mp3", 1024, 0, 0)

  /**
    * Generates a test event based on the given index.
    *
    * @param index the index
    * @return the test event with this index
    */
  private def progressEvent(index: Int): PlaybackProgressEvent =
    PlaybackProgressEvent(bytesProcessed = index * 1024, playbackTime = index * 1000.seconds,
      currentSource = TestAudioSource)

/**
  * Test class for [[EventManagerActor]].
  */
class EventManagerActorSpec extends AnyFlatSpec with Matchers with ActorTestKitSupport with AsyncTestHelper:

  import EventManagerActorSpec.*

  "EventManagerActor" should "publish events to registered listeners" in:
    val event = AudioSourceStartedEvent(TestAudioSource)
    val listener1 = testKit.createTestProbe[PlayerEvent]()
    val listener2 = testKit.createTestProbe[PlayerEvent]()

    val eventManager = testKit.spawn(EventManagerActor[PlayerEvent]())
    eventManager ! EventManagerActor.RegisterListener(listener1.ref)
    eventManager ! EventManagerActor.RegisterListener(listener2.ref)
    eventManager ! EventManagerActor.Publish(event)

    listener1.expectMessage(event)
    listener2.expectMessage(event)

  it should "support removing event listeners" in:
    val event1 = progressEvent(1)
    val event2 = progressEvent(2)
    val listener1 = testKit.createTestProbe[PlayerEvent]()
    val listener2 = testKit.createTestProbe[PlayerEvent]()

    val eventManager = testKit.spawn(EventManagerActor[PlayerEvent]())
    eventManager ! EventManagerActor.RegisterListener(listener1.ref)
    eventManager ! EventManagerActor.RegisterListener(listener2.ref)
    eventManager ! EventManagerActor.Publish(event1)

    eventManager ! EventManagerActor.RemoveListener(listener2.ref)
    eventManager ! EventManagerActor.Publish(event2)

    listener1.expectMessage(event1)
    listener1.expectMessage(event2)
    listener2.expectMessage(event1)
    listener2.expectNoMessage(500.millis)

  it should "remove an event listener that died" in:
    val mockListener = testKit.spawn(Behaviors.receiveMessage[PlayerEvent] { _ =>
      Behaviors.stopped
    })
    val deadLetterProbe = testKit.createDeadLetterProbe()

    val eventManager = testKit.spawn(EventManagerActor[PlayerEvent]())
    eventManager ! EventManagerActor.RegisterListener(mockListener)
    eventManager ! EventManagerActor.Publish(progressEvent(1))
    deadLetterProbe.expectTerminated(mockListener)

    val listener = testKit.createTestProbe[PlayerEvent]()
    eventManager ! EventManagerActor.RegisterListener(listener.ref)
    eventManager ! EventManagerActor.Publish(progressEvent(2))
    listener.expectMessageType[PlaybackProgressEvent]

    deadLetterProbe.expectNoMessage(500.millis)

  it should "stop itself when receiving the Stop command" in:
    val deadLetterProbe = testKit.createDeadLetterProbe()
    val eventManager = testKit.spawn(EventManagerActor[PlayerEvent]())

    eventManager ! EventManagerActor.Stop()

    val message = EventManagerActor.Publish[PlayerEvent](progressEvent(42))
    eventManager ! message
    val deadLetter = deadLetterProbe.expectMessageType[DeadLetter]
    deadLetter.message should be(message)

  it should "provide an actor to publish events" in:
    implicit val timeout: Timeout = 3.seconds
    val eventManager = testKit.spawn(EventManagerActor[PlayerEvent]())

    val futPublisher: Future[EventManagerActor.PublisherReference[PlayerEvent]] =
      eventManager.ask(ref => EventManagerActor.GetPublisher(ref))
    val publisher = futureResult(futPublisher).publisher

    val event = progressEvent(28)
    val probe = testKit.createTestProbe[PlayerEvent]()
    eventManager ! EventManagerActor.RegisterListener(probe.ref)
    publisher ! event

    probe.expectMessage(event)
