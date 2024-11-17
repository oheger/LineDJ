/*
 * Copyright 2015-2024 The Developers Team.
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

import de.oliver_heger.linedj.player.engine.actors.ScheduledInvocationActor
import de.oliver_heger.linedj.player.engine.radio.stream.RadioStreamHandleManagerActor.{GetStreamHandle, ReleaseStreamHandle}
import de.oliver_heger.linedj.player.engine.radio.{CurrentMetadata, RadioSource}
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

object RadioStreamHandleManagerActorSpec:
  /** A test radio source. */
  private val TestRadioSource = RadioSource("testRadioSource")

  /** The stream name that is expected to be passed to the actor. */
  private val TestStreamName = "myTestRadioStream"

  /** Constant for the time stream actors can remain in the cache. */
  private val CacheTime = 5.seconds

  /** The buffer size passed to the actor. */
  private val PlaybackBufferSize = 17
end RadioStreamHandleManagerActorSpec

/**
  * Test class for [[RadioStreamHandleManagerActor]].
  */
class RadioStreamHandleManagerActorSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem)
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(classic.ActorSystem("RadioStreamHandleManagerActorSpec"))

  /** The test kit for testing types actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()
    TestKit shutdownActorSystem system
    super.afterAll()

  import RadioStreamHandleManagerActorSpec.*

  "RadioStreamHandleManagerActor" should "return a cached entry" in :
    val handle = mock[RadioStreamHandle]
    val metadata = CurrentMetadata("myMetadata")
    val helper = new ManagerTestHelper

    helper.sendCommand(ReleaseStreamHandle(TestRadioSource, handle, Some(metadata)))
      .expectCacheInvalidation()

    verify(handle, timeout(3000)).detach()

    val response = helper.requestStreamHandle()
    response.optLastMetadata should be(Some(metadata))
    response.triedStreamHandle should be(Success(handle))

    helper.sendCacheInvalidation()
    verify(handle, never()).cancelStream()

  it should "return a newly created stream handle" in :
    val handle = mock[RadioStreamHandle]
    val helper = new ManagerTestHelper

    val response = helper.prepareHandleCreation(handle)
      .requestStreamHandle()

    response.triedStreamHandle should be(Success(handle))
    response.optLastMetadata shouldBe empty

  it should "handle an error from the handle factory" in :
    val exception = new IllegalStateException("test exception: Could not create stream handle.")
    val helper = new ManagerTestHelper

    val response = helper.prepareHandleCreation(Future.failed(exception))
      .requestStreamHandle()

    response.triedStreamHandle should be(Failure(exception))

  it should "cancel a stream if the cache time is exceeded" in :
    val handle = mock[RadioStreamHandle]
    val newHandle = mock[RadioStreamHandle]
    val helper = new ManagerTestHelper

    val response = helper.sendCommand(ReleaseStreamHandle(TestRadioSource, handle, None))
      .expectCacheInvalidation()
      .sendCacheInvalidation()
      .prepareHandleCreation(newHandle)
      .requestStreamHandle()

    verify(handle).cancelStream()
    response.triedStreamHandle should be(Success(newHandle))

  it should "stop itself when receiving a Stop command" in :
    val helper = new ManagerTestHelper

    val probe = helper.sendCommand(RadioStreamHandleManagerActor.Stop)
      .deathWatchProbe

    probe.expectMsgType[classic.Terminated]

  /**
    * A test helper class for managing an actor instance and its dependencies.
    */
  private class ManagerTestHelper:
    /** Test probe for the scheduler actor. */
    private val probeScheduler = testKit.createTestProbe[ScheduledInvocationActor.ScheduledInvocationCommand]()

    /** A mock for the stream builder. */
    private val streamBuilder = mock[RadioStreamBuilder]

    /** Stores the result of a call to the handle factory. */
    private val handleCreationResult = new AtomicReference[Future[RadioStreamHandle]]

    /** A stub for the factory for stream handles. */
    private val handleFactory = createHandleFactory()

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
    def sendCommand(command: RadioStreamHandleManagerActor.RadioStreamHandleCommand): ManagerTestHelper =
      managerActor ! command
      this

    /**
      * Verifies that the stream builder was invoked to create a new stream.
      *
      * @return this test helper
      */
    def verifyRadioStreamCreated(): ManagerTestHelper =
      val capture = ArgumentCaptor.forClass(classOf[RadioStreamBuilder.RadioStreamParameters[Any, Any]])
      verify(streamBuilder, timeout(3000)).buildRadioStream(capture.capture())(any(), any())
      capture.getValue.streamUri should be(TestRadioSource.uri)
      capture.getValue.bufferSize should be(PlaybackBufferSize)
      this

    /**
      * Expects that the scheduled invocation actor was called to remove an
      * actor instance from the cache. The command is recorded, so that the
      * invalidation can be triggered later.
      *
      * @return this test helper
      */
    def expectCacheInvalidation(): ManagerTestHelper =
      val command = probeScheduler.expectMessageType[ScheduledInvocationActor.ActorInvocationCommand]
      command.delay should be(CacheTime)
      cacheInvalidation.set(command.invocation)
      this

    /**
      * Triggers a cache invalidation recorded by ''expectCacheInvalidation()''.
      *
      * @return this cache helper
      */
    def sendCacheInvalidation(): ManagerTestHelper =
      val invocation = cacheInvalidation.get()
      invocation should not be null
      invocation.send()
      this

    /**
      * Requests the stream handle for the test radio source from the manager
      * actor under test.
      *
      * @return the stream handle returned from the manager
      */
    def requestStreamHandle(): RadioStreamHandleManagerActor.GetStreamHandleResponse =
      val probeClient = testKit.createTestProbe[RadioStreamHandleManagerActor.GetStreamHandleResponse]()
      val params = RadioStreamHandleManagerActor.GetStreamHandleParameters(TestRadioSource, TestStreamName)
      sendCommand(GetStreamHandle(params, probeClient.ref))
      val response = probeClient.expectMessageType[RadioStreamHandleManagerActor.GetStreamHandleResponse]
      response.source should be(TestRadioSource)
      response

    /**
      * Prepares the mock for the handle factory to expect the creation of a 
      * handle with the parameters for the test radio source. The mock returns
      * the provided handle.
      *
      * @param handle the handle to be returned by the mock
      * @return this test helper
      */
    def prepareHandleCreation(handle: RadioStreamHandle): ManagerTestHelper =
      prepareHandleCreation(Future.successful(handle))

    /**
      * Prepares the mock for the handle factory to expect the creation of a 
      * handle with the parameters for the test radio source and to return the
      * given result. With this function, also failures to create a handle can
      * be tested.
      *
      * @param result the result to be returned by the handle factory
      * @return this test helper
      */
    def prepareHandleCreation(result: Future[RadioStreamHandle]): ManagerTestHelper =
      handleCreationResult.set(result)
      this

    /**
      * Returns a [[TestProbe]] that watches the actor under test, so it can be
      * used to check whether it has terminated.
      *
      * @return the watching probe
      */
    def deathWatchProbe: TestProbe =
      val probe = TestProbe()
      probe watch managerActor.toClassic
      probe

    /**
      * Creates a stream handle factory implementation that checks the passed
      * in parameters and returns a configured result future.
      *
      * @return the stream handle factory to be used
      */
    private def createHandleFactory(): RadioStreamHandle.Factory =
      new RadioStreamHandle.Factory:
        override def create(builder: RadioStreamBuilder, streamUri: String, bufferSize: Int, streamName: String)
                           (using system: ActorSystem): Future[RadioStreamHandle] =
          builder should be(streamBuilder)
          streamUri should be(TestRadioSource.uri)
          bufferSize should be(PlaybackBufferSize)
          streamName should be(TestStreamName)
          val result = handleCreationResult.get()
          result should not be null
          result

    /**
      * Creates the actor instance to be tested.
      *
      * @return the actor under test
      */
    private def createManagerActor(): ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand] =
      val behavior = RadioStreamHandleManagerActor.behavior(
        streamBuilder,
        handleFactory,
        probeScheduler.ref,
        CacheTime,
        PlaybackBufferSize
      )
      testKit.spawn(behavior)
