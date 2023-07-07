/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.radio.stream

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe => TypedTestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.testkit.{TestKit, TestProbe}
import akka.{actor => classic}
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.PlayerConfigSpec.TestPlayerConfig
import de.oliver_heger.linedj.player.engine.actors.ScheduledInvocationActor
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceConfigTestHelper.radioSource
import de.oliver_heger.linedj.player.engine.radio.{CurrentMetadata, MetadataNotSupported, RadioEvent, RadioMetadata, RadioMetadataEvent, RadioSource}
import de.oliver_heger.linedj.player.engine.{AudioSource, PlayerConfig}
import org.mockito.ArgumentMatchers.{any, eq => eqArg}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.Future
import scala.concurrent.duration._

object RadioStreamManagerActorSpec {
  /** A test radio source. */
  private val TestRadioSource = RadioSource("testRadioSource")

  /** A test audio source simulating the resolved source. */
  private val ResolvedSource = AudioSource("resolvedUri", 0, 0, 0)

  /** Constant for the time stream actors can remain in the cache. */
  private val CacheTime = 5.seconds

  /** A dummy source listener function ignoring its invocation. */
  private val DummySourceListener: RadioStreamActor.SourceListener = (_, _) => {}

  /**
    * An internal data class to represent messages a client actor receives
    * from the manager actor.
    *
    * @param data the payload of the message
    */
  private case class ReceivedMessage(data: AnyRef)
}

class RadioStreamManagerActorSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(classic.ActorSystem("RadioStreamManagerActorSpec"))

  /** The test kit for testing types actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit = {
    testKit.shutdownTestKit()
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import RadioStreamManagerActorSpec._

  /**
    * Helper function to test whether a correct radio event with metadata is
    * generated.
    *
    * @param eventActor the event actor probe which is passed the event
    * @param metadata   the expected metadata in the event
    */
  private def checkMetadataEvent(eventActor: TypedTestProbe[RadioEvent], metadata: RadioMetadata): Unit = {
    eventActor.expectMessageType[RadioMetadataEvent] match {
      case RadioMetadataEvent(source, eventMetadata, time) =>
        source should be(TestRadioSource)
        eventMetadata should be(metadata)
        math.abs(java.time.Duration.between(time, LocalDateTime.now()).toMillis) should be < 3000L
      case e =>
        fail("Unexpected event: " + e)
    }
  }

  "RadioStreamManagerActor" should "reuse an actor instance from the cache" in {
    val streamActor = TestProbe()
    val helper = new StreamManagerTestHelper

    helper.sendCommand(RadioStreamManagerActor.ReleaseStreamActor(TestRadioSource, streamActor.ref, ResolvedSource))
      .expectCacheInvalidation()

    streamActor.expectMsg(RadioStreamActor.UpdateEventActor(None))
    streamActor.expectNoMessage(200.millis)

    val eventActor = testKit.createTestProbe[RadioEvent]()
    val streamActorFromManager = helper.requestStreamActor(optEventActor = Some(eventActor.ref))

    streamActorFromManager should be(streamActor.ref)
    streamActor.expectMsg(RadioStreamActor.UpdateEventActor(Some(eventActor.ref)))
    checkMetadataEvent(eventActor, MetadataNotSupported)

    helper.sendCacheInvalidation()
    streamActor.expectNoMessage(200.millis)
  }

  it should "send messages in the correct order" in {
    val eventQueue = new LinkedBlockingQueue[ReceivedMessage]
    val streamActor = TestProbe()
    val helper = new StreamManagerTestHelper
    helper.sendCommand(RadioStreamManagerActor.ReleaseStreamActor(TestRadioSource, streamActor.ref, ResolvedSource))

    val clientBehavior = Behaviors.setup[ReceivedMessage] { context =>
      val sourceListener: RadioStreamActor.SourceListener = (source, ref) => {
        context.self ! ReceivedMessage((source, ref))
      }
      val responseAdapter = context.messageAdapter[RadioStreamManagerActor.StreamActorResponse] { response =>
        ReceivedMessage(response)
      }
      helper.sendStreamActorRequest(responseAdapter, sourceListener)

      Behaviors.receiveMessage {
        msg: ReceivedMessage =>
          eventQueue offer msg
          Behaviors.same
      }
    }
    testKit.spawn(clientBehavior)

    val actorResponse = RadioStreamManagerActor.StreamActorResponse(TestRadioSource, streamActor.ref)
    eventQueue.poll(3, TimeUnit.SECONDS) should be(ReceivedMessage(actorResponse))
    eventQueue.poll(3, TimeUnit.SECONDS) should be(ReceivedMessage((ResolvedSource, streamActor.ref)))
  }

  it should "generate a metadata event when reusing an actor if possible" in {
    val metadata = CurrentMetadata("some radio stream metadata")
    val streamActor = TestProbe()
    val eventActor = testKit.createTestProbe[RadioEvent]()
    val helper = new StreamManagerTestHelper

    helper.sendCommand(RadioStreamManagerActor.ReleaseStreamActor(TestRadioSource, streamActor.ref, ResolvedSource,
      Some(metadata)))
    streamActor.expectMsg(RadioStreamActor.UpdateEventActor(None))
    helper.requestStreamActor(optEventActor = Some(eventActor.ref))

    checkMetadataEvent(eventActor, metadata)
  }

  it should "close an actor if the cache time is exceeded" in {
    val streamActor = TestProbe()
    val helper = new StreamManagerTestHelper

    helper.sendCommand(RadioStreamManagerActor.ReleaseStreamActor(TestRadioSource, streamActor.ref, ResolvedSource))
      .expectCacheInvalidation()
      .sendCacheInvalidation()

    streamActor.expectMsg(RadioStreamActor.UpdateEventActor(None))
    streamActor.expectMsg(CloseRequest)

    val nextStreamActor = helper.requestStreamActor()
    nextStreamActor should not be streamActor.ref
    helper.verifyStreamActorCreated()
  }

  it should "stop itself when receiving a Stop command" in {
    val helper = new StreamManagerTestHelper

    val probe = helper.sendCommand(RadioStreamManagerActor.Stop)
      .deathWatchProbe

    probe.expectMsgType[classic.Terminated]
  }

  it should "not stop itself before all actors in the cache have been closed" in {
    def expectClose(probe: TestProbe, reply: Boolean = true): Unit = {
      probe.expectMsg(RadioStreamActor.UpdateEventActor(None))
      probe.expectMsg(CloseRequest)
      if (reply) {
        probe.reply(CloseAck(probe.ref))
      }
    }

    val source0 = radioSource(0)
    val source2 = radioSource(2)
    val source3 = radioSource(3)
    val streamActor0 = TestProbe()
    val streamActor1 = TestProbe()
    val streamActor2 = TestProbe()
    val streamActor3 = TestProbe()
    val helper = new StreamManagerTestHelper

    helper.sendCommand(RadioStreamManagerActor.ReleaseStreamActor(source0, streamActor0.ref, ResolvedSource))
      .expectCacheInvalidation()
      .sendCacheInvalidation()
    expectClose(streamActor0)

    helper.sendCommand(RadioStreamManagerActor.ReleaseStreamActor(TestRadioSource, streamActor1.ref, ResolvedSource))
      .sendCommand(RadioStreamManagerActor.ReleaseStreamActor(source2, streamActor2.ref, ResolvedSource))
      .sendCommand(RadioStreamManagerActor.ReleaseStreamActor(source3, streamActor3.ref, ResolvedSource))
      .expectCacheInvalidation()
      .sendCacheInvalidation()
      .sendCommand(RadioStreamManagerActor.Stop)

    List(streamActor1, streamActor2, streamActor3) foreach { a =>
      expectClose(a, reply = a != streamActor2)
    }
    val watcher = helper.deathWatchProbe
    watcher.expectNoMessage(200.millis)

    streamActor2.reply(CloseAck(streamActor2.ref))
    watcher.expectMsgType[classic.Terminated]
  }

  /**
    * A test helper class managing a test actor instance and its dependencies.
    */
  private class StreamManagerTestHelper {
    /** Test probe for the scheduler actor. */
    private val probeScheduler = testKit.createTestProbe[ScheduledInvocationActor.ScheduledInvocationCommand]()

    /** A mock for the stream builder. */
    private val streamBuilder = createStreamBuilder()

    /** Stores the actor invocation to invalidate the cache. */
    private val cacheInvalidation = new AtomicReference[ScheduledInvocationActor.ActorInvocation]

    /** The actor to be tested. */
    private val managerActor = createManagerActor()

    /**
      * Sends the given command to the actor to be tested.
      *
      * @param command the command to send
      * @return this test helper
      */
    def sendCommand(command: RadioStreamManagerActor.RadioStreamManagerCommand): StreamManagerTestHelper = {
      managerActor ! command
      this
    }

    /**
      * Verifies that the stream builder was invoked to create a new stream,
      * which means that a stream actor instance has been created.
      *
      * @return this test helper
      */
    def verifyStreamActorCreated(): StreamManagerTestHelper = {
      verify(streamBuilder, timeout(3000)).buildRadioStream(eqArg(TestPlayerConfig), eqArg(TestRadioSource.uri),
        any(), any())(any(), any())
      this
    }

    /**
      * Expects that the scheduled invocation actor was called to remove an
      * actor instance from the cache. The command is recorded, so that the
      * invalidation can be triggered later.
      *
      * @return this test helper
      */
    def expectCacheInvalidation(): StreamManagerTestHelper = {
      val command = probeScheduler.expectMessageType[ScheduledInvocationActor.ActorInvocationCommand]
      command.delay should be(CacheTime)
      cacheInvalidation set command.invocation
      this
    }

    /**
      * Triggers a cache invalidation recorded by ''expectCacheInvalidation()''.
      *
      * @return this cache helper
      */
    def sendCacheInvalidation(): StreamManagerTestHelper = {
      val invocation = cacheInvalidation.get()
      invocation should not be null
      invocation.send()
      this
    }

    /**
      * Requests the stream actor for the test radio source from the manager
      * actor under test.
      *
      * @param optEventActor  an optional event listener actor
      * @param sourceListener the source listener function
      * @return the stream actor returned from the manager
      */
    def requestStreamActor(optEventActor: Option[ActorRef[RadioEvent]] = None,
                           sourceListener: RadioStreamActor.SourceListener = DummySourceListener): classic.ActorRef = {
      val probeClient = testKit.createTestProbe[RadioStreamManagerActor.StreamActorResponse]()
      sendStreamActorRequest(probeClient.ref, sourceListener, optEventActor)
      val response = probeClient.expectMessageType[RadioStreamManagerActor.StreamActorResponse]
      response.source should be(TestRadioSource)
      response.streamActor
    }

    /**
      * Sends a message to the actor under test to request a radio stream actor
      * for the given parameters.
      *
      * @param client         the client expecting the response
      * @param sourceListener the source listener function
      * @param optEventActor  an optional event listener actor
      */
    def sendStreamActorRequest(client: ActorRef[RadioStreamManagerActor.StreamActorResponse],
                               sourceListener: RadioStreamActor.SourceListener,
                               optEventActor: Option[ActorRef[RadioEvent]] = None): Unit = {
      val eventActor = optEventActor getOrElse testKit.createTestProbe[RadioEvent]().ref
      val params = RadioStreamManagerActor.StreamActorParameters(TestRadioSource, sourceListener, eventActor)
      managerActor ! RadioStreamManagerActor.GetStreamActor(params, client)
    }

    /**
      * Returns a [[TestProbe]] that watches the actor under test, so it can be
      * used to check whether it has terminated.
      *
      * @return the watching probe
      */
    def deathWatchProbe: TestProbe = {
      val probe = TestProbe()
      probe watch managerActor.toClassic
      probe
    }

    /**
      * Creates a mock for a stream builder. The mock is prepared to answer a
      * request to create a stream with a failed future. This is fine, since it
      * only has to be tested whether it has been invoked at all with plausible
      * arguments.
      *
      * @return
      */
    private def createStreamBuilder(): RadioStreamBuilder = {
      val builder = mock[RadioStreamBuilder]
      when(builder.buildRadioStream(any(), any(), any(), any())(any(), any()))
        .thenReturn(Future.failed(new IllegalStateException("Test exception")))
      builder
    }

    /**
      * Creates the actor instance to be tested.
      *
      * @return the actor under test
      */
    private def createManagerActor(): ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand] = {
      val behavior = RadioStreamManagerActor.behavior(TestPlayerConfig, streamBuilder, probeScheduler.ref, CacheTime)
      testKit.spawn(behavior)
    }
  }
}
