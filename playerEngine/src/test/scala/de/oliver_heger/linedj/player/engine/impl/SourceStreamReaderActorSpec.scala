/*
 * Copyright 2015-2022 The Developers Team.
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

import java.util.concurrent.atomic.AtomicInteger
import akka.actor.SupervisorStrategy.Stop
import akka.actor.{ActorRef, ActorSystem, OneForOneStrategy, Props, Terminated}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.ByteString
import de.oliver_heger.linedj.SupervisionTestActor
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.impl.LocalBufferActor.BufferDataResult
import de.oliver_heger.linedj.player.engine.{AudioSource, PlayerConfig}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.ArgumentMatchers.{any, eq => argEq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object SourceStreamReaderActorSpec {
  /** A reference pointing to an audio stream. */
  private val AudioStreamRef = StreamReference("music.mp3")

  /** A reference to a m3u file. */
  private val PlaylistStreamRef = StreamReference("playlist.m3u")

  /** Name of a blocking dispatcher. */
  private val BlockingDispatcherName = "BlockingDispatcher"

  /** The class for the buffer actor. */
  private val ClassBufferActor = classOf[StreamBufferActor]

  /** A default request message for audio data. */
  private val DataRequest = PlaybackActor.GetAudioData(42)

  /** A test player configuration. */
  private val Config = PlayerConfig(mediaManagerActor = null, actorCreator = (_, _) => null,
    blockingDispatcherName = Some(BlockingDispatcherName))

  /**
    * Creates an object with pseudo audio data.
    *
    * @return the data object
    */
  private def audioData(): BufferDataResult = {
    val dataArray = new Array[Byte](42)
    BufferDataResult(ByteString(dataArray))
  }
}

/**
  * Test class for ''SourceStreamReaderActor''.
  */
class SourceStreamReaderActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import SourceStreamReaderActorSpec._

  def this() = this(ActorSystem("SourceStreamReaderActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A SourceStreamReaderActor" should "create a buffer child actor" in {
    val helper = new SourceStreamReaderActorTestHelper
    helper.createTestActor(AudioStreamRef)

    awaitCond(helper.numberOfChildrenCreated == 1)
  }

  it should "pass a request for audio data to the buffer actor" in {
    val helper = new SourceStreamReaderActorTestHelper
    val actor = helper.createTestActor(AudioStreamRef)

    actor ! DataRequest
    helper.probeBufferActor.expectMsg(DataRequest)
    helper.numberOfChildrenCreated should be(1)
  }

  it should "resolve all passed in references" in {
    val helper = new SourceStreamReaderActorTestHelper
    helper.createTestActor(AudioStreamRef)

    helper.verifyM3uResolveOperation(AudioStreamRef)
  }

  it should "create a buffer actor when the audio stream is resolved" in {
    val helper = new SourceStreamReaderActorTestHelper
    val actor = helper.createTestActor(PlaylistStreamRef)

    helper.setM3uResolveResult(Success(AudioStreamRef))
    actor ! DataRequest
    helper.probeBufferActor.expectMsg(DataRequest)
    helper.numberOfChildrenCreated should be(1)
  }

  it should "stop itself if resolving of the audio stream fails" in {
    val helper = new SourceStreamReaderActorTestHelper
    val actor = helper.createTestActor(PlaylistStreamRef)

    helper.setM3uResolveResult(Failure(new IllegalStateException("Failure")))
    val probe = TestProbe()
    probe watch actor
    probe.expectTerminated(actor)
  }

  it should "park an audio data request until the buffer actor becomes available" in {
    val helper = new SourceStreamReaderActorTestHelper
    val actor = helper.createTestActor(PlaylistStreamRef)

    actor ! DataRequest
    helper.setM3uResolveResult(Success(AudioStreamRef))
    helper.probeBufferActor.expectMsg(DataRequest)
  }

  it should "send back the answer to an audio data request" in {
    val helper = new SourceStreamReaderActorTestHelper
    val actor = helper.createTestActor(AudioStreamRef)
    actor ! DataRequest
    val data = audioData()

    actor ! data
    expectMsg(data)
  }

  it should "ignore an unexpected audio data message" in {
    val helper = new SourceStreamReaderActorTestHelper
    val actor = helper.createTestActor(AudioStreamRef)

    actor receive audioData()
  }

  it should "reject data requests if a request is in progress" in {
    val helper = new SourceStreamReaderActorTestHelper
    val actor = helper.createTestActor(AudioStreamRef)
    actor ! DataRequest

    val req2 = PlaybackActor.GetAudioData(1)
    actor ! req2
    val err = expectMsgType[PlaybackProtocolViolation]
    err.msg should be(req2)
    err.errorText should be("[SourceStreamReaderActor] Unexpected request for audio data!")
  }

  it should "process multiple data requests" in {
    val helper = new SourceStreamReaderActorTestHelper
    val actor = helper.createTestActor(AudioStreamRef)
    actor ! DataRequest
    helper.probeBufferActor.expectMsg(DataRequest)
    val data = audioData()
    actor ! data
    expectMsg(data)

    actor ! DataRequest
    helper.probeBufferActor.expectMsg(DataRequest)
  }

  it should "repeat data requests if no data is returned" in {
    val helper = new SourceStreamReaderActorTestHelper
    val actor = helper.createTestActor(AudioStreamRef)
    actor ! DataRequest
    helper.probeBufferActor.expectMsg(DataRequest)

    actor ! BufferDataResult(ByteString.empty)
    helper.probeBufferActor.expectMsg(DataRequest)
    val data = audioData()
    actor ! data
    expectMsg(data)
  }

  it should "ignore an unexpected data message with length 0" in {
    val helper = new SourceStreamReaderActorTestHelper
    val actor = helper.createTestActor(AudioStreamRef)

    actor receive BufferDataResult(ByteString.empty)
  }

  it should "delegate a close request to the buffer actor" in {
    val helper = new SourceStreamReaderActorTestHelper
    val actor = helper.createTestActor(AudioStreamRef)

    actor ! CloseRequest
    helper.probeBufferActor.expectMsg(CloseRequest)
  }

  it should "ignore further messages after a close request" in {
    val helper = new SourceStreamReaderActorTestHelper
    val actor = helper.createTestActor(AudioStreamRef)
    actor ! CloseRequest
    helper.probeBufferActor.expectMsg(CloseRequest)

    actor ! DataRequest
    helper.probeBufferActor.expectNoMessage(500.milliseconds)
  }

  it should "send a CloseAck directly if there is not yet a buffer actor" in {
    val helper = new SourceStreamReaderActorTestHelper
    val actor = helper.createTestActor(PlaylistStreamRef)

    actor ! CloseRequest
    expectMsg(CloseAck(actor))
  }

  it should "send a CloseAck when the buffer actor has been closed" in {
    val helper = new SourceStreamReaderActorTestHelper
    val actor = helper.createTestActor(AudioStreamRef)
    actor ! CloseRequest

    actor.tell(CloseAck(helper.probeBufferActor.ref), helper.probeBufferActor.ref)
    expectMsg(CloseAck(actor))
  }

  it should "ignore an unexpected CloseAck message" in {
    val helper = new SourceStreamReaderActorTestHelper
    val actor = helper.createTestActor(AudioStreamRef)
    actor ! CloseRequest

    actor ! CloseAck(testActor)
    expectNoMessage(500.milliseconds)
  }

  it should "create a correct Props object" in {
    val listener = TestProbe()
    val props = SourceStreamReaderActor(Config, AudioStreamRef, listener.ref)

    classOf[SourceStreamReaderActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[ChildActorFactory].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should have size 4
    props.args.init should contain theSameElementsAs List(Config, AudioStreamRef, listener.ref)
    props.args.last shouldBe a[M3uReader]
  }

  it should "notify the source listener when an audio stream is passed in" in {
    val helper = new SourceStreamReaderActorTestHelper
    helper.createTestActor(AudioStreamRef)

    helper.probeSourceListener.expectMsg(AudioSource(AudioStreamRef.uri, Long.MaxValue, 0, 0))
  }

  it should "notify the source listener when the audio stream has been resolved" in {
    val helper = new SourceStreamReaderActorTestHelper
    helper.createTestActor(PlaylistStreamRef)
    helper.setM3uResolveResult(Success(AudioStreamRef))

    helper.probeSourceListener.expectMsg(AudioSource(AudioStreamRef.uri, Long.MaxValue, 0, 0))
  }

  it should "define a delegating supervision strategy" in {
    val strategy = OneForOneStrategy() {
      case _: java.io.IOException => Stop
    }
    val probe = TestProbe()
    val parent = SupervisionTestActor(system, strategy,
      SourceStreamReaderActor(Config.copy(blockingDispatcherName = None),
        StreamReference("err://non-existing.err/test.m3u"), probe.ref))
    val actor = parent.underlyingActor.childActor

    probe watch actor
    probe.expectMsgType[Terminated]
  }

  /**
    * A test helper class managing dependencies of a test actor.
    */
  private class SourceStreamReaderActorTestHelper {
    /** Test probe for the buffer actor. */
    val probeBufferActor: TestProbe = TestProbe()

    /** Test probe for an audio source listener actor. */
    val probeSourceListener: TestProbe = TestProbe()

    /** A counter for the child actors that have been created. */
    private val childCount = new AtomicInteger

    /** The promise for the future returned by the M3uReader mock. */
    private val m3uPromise = Promise[StreamReference]()

    /** A mock for the object that resolves m3u URIs. */
    private val m3uReader = createM3uReader()

    /**
      * Creates a test actor that operates on the specified reference.
      *
      * @param streamRef the stream reference
      * @return the test actor reference
      */
    def createTestActor(streamRef: StreamReference): TestActorRef[SourceStreamReaderActor] =
      TestActorRef[SourceStreamReaderActor](createProps(streamRef))

    /**
      * Returns the number of children that have been created for the test
      * actor.
      *
      * @return the number of created child actors
      */
    def numberOfChildrenCreated: Int = childCount.get()

    /**
      * Checks whether the M3u reader was invoked to resolve the given stream
      * reference.
      *
      * @param streamRef the expected reference
      * @return this test helper
      */
    def verifyM3uResolveOperation(streamRef: StreamReference): SourceStreamReaderActorTestHelper = {
      verify(m3uReader, timeout(1000)).resolveAudioStream(argEq(Config), argEq(streamRef))(any(), any())
      this
    }

    /**
      * Sets the result produced by the M3u reader.
      *
      * @param result the result
      * @return this test helper
      */
    def setM3uResolveResult(result: Try[StreamReference]): SourceStreamReaderActorTestHelper = {
      result match {
        case Failure(exception) => m3uPromise.failure(exception)
        case Success(value) => m3uPromise.success(value)
      }
      this
    }

    /**
      * Creates a mock for an ''M3uReader'' and configures it to expect a
      * resolve operation. If a the reference to be resolved points to a
      * playlist, the mock returns a promise, and the result can be set later
      * via ''setM3uResolveResult()''. Otherwise, the mock returns a successful
      * future with the same reference.
      *
      * @return the mock M3u reader
      */
    private def createM3uReader(): M3uReader = {
      val reader = mock[M3uReader]
      when(reader.resolveAudioStream(any(), argEq(PlaylistStreamRef))(any(), any())).thenReturn(m3uPromise.future)
      when(reader.resolveAudioStream(any(), argEq(AudioStreamRef))(any(), any()))
        .thenReturn(Future.successful(AudioStreamRef))
      reader
    }

    /**
      * Creates the properties for a test actor.
      *
      * @param streamRef the reference to the stream to be processed
      * @return the properties
      */
    private def createProps(streamRef: StreamReference): Props =
      Props(new SourceStreamReaderActor(Config, streamRef, probeSourceListener.ref, m3uReader)
        with ChildActorFactory {
        /**
          * @inheritdoc This implementation returns corresponding test
          *             probes after testing the arguments.
          */
        override def createChildActor(p: Props): ActorRef = {
          childCount.incrementAndGet()
          p.dispatcher should be(BlockingDispatcherName)
          p.actorClass() match {
            case ClassBufferActor =>
              p.args should contain theSameElementsAs List(Config, AudioStreamRef)
              probeBufferActor.ref
          }
        }
      })
  }

}
