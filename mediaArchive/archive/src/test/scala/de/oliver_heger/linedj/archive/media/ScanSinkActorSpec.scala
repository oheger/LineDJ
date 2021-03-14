/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.archive.media

import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.StateTestHelper
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.{MediumID, MediumInfo}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}
import scala.util.Failure

object ScanSinkActorSpec {
  /** Maximum buffer size used by tests. */
  private val BufferSize = 42

  /** A test sequence number. */
  private val SeqNo = 111

  /**
    * Creates a test medium ID with the given index.
    *
    * @param idx the index
    * @return the test medium ID
    */
  private def createMediumID(idx: Int): MediumID = {
    val uri = "/music/test/medium" + idx
    MediumID(uri, Some(uri + "/playlist.settings"))
  }

  /**
    * Generates an enhanced scan result for the test medium with the given
    * index.
    *
    * @param idx the index
    * @return the scan result for this medium
    */
  private def createScanResult(idx: Int): EnhancedMediaScanResult = {
    val mid = createMediumID(idx)
    val files = (1 to idx).map(i => FileData(s"${mid.mediumURI}/music/song$i.mp3", i * 10))
    val scanResult = MediaScanResult(Paths get mid.mediumURI, Map(mid -> files.toList))
    EnhancedMediaScanResult(scanResult, Map(mid -> s"foo_$idx"), Map.empty)
  }

  /**
    * Creates a medium info object for the test medium with the given index.
    *
    * @param idx the index
    * @return the medium information for this medium
    */
  private def createMediumInfo(idx: Int): MediumInfo =
    MediumInfo(mediumID = createMediumID(idx), name = "info" + idx, description = "",
      orderMode = "", orderParams = "", checksum = "")

  /**
    * Creates a map with medium information for the specified test medium.
    *
    * @param idx the index of the test medium
    * @return the map with medium information for this test medium
    */
  private def createMediaInfoMap(idx: Int): Map[MediumID, MediumInfo] = {
    val info = createMediumInfo(idx)
    Map(info.mediumID -> info)
  }

  /**
    * Creates a combined result object for the specified test medium.
    *
    * @param idx the index of the test medium
    * @return the combined result object for this medium
    */
  private def createCombinedResult(idx: Int): CombinedMediaScanResult =
    CombinedMediaScanResult(createScanResult(idx), createMediaInfoMap(idx))

  /**
    * Checks that no message was sent to the given test probe.
    *
    * @param probe the test probe
    */
  private def expectNoMessageToProbe(probe: TestProbe): Unit = {
    val Ping = new Object
    probe.ref ! Ping
    probe.expectMsg(Ping)
  }
}

/**
  * Test class for ''ScanSinkActor''.
  */
class ScanSinkActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("ScanSinkActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import ScanSinkActorSpec._

  "A ScanSinkActor" should "use the default sink update service" in {
    val manager = TestProbe()
    val actor = TestActorRef[ScanSinkActor](Props(classOf[ScanSinkActor], manager.ref,
      Promise[Unit](), BufferSize, SeqNo))

    actor.underlyingActor.sinkUpdateService should be(ScanSinkUpdateServiceImpl)
  }

  it should "ACK an init message" in {
    val helper = new SinkActorTestHelper

    helper post ScanSinkActor.Init
    expectMsg(ScanSinkActor.Ack)
  }

  it should "pass combined results for a new scan result to the manager" in {
    val res = createScanResult(1)
    val results = List(createCombinedResult(1), createCombinedResult(2))
    val messages = SinkTransitionMessages(results, Nil, processingDone = false)
    val helper = new SinkActorTestHelper

    helper.stub(messages, ScanSinkUpdateServiceImpl.InitialState) {
      _.handleNewScanResult(res, testActor, BufferSize)
    }
      .post(res)
      .expectCombinedResults(results)
  }

  it should "send ACK messages to actors when receiving a new scan result" in {
    val probeAck1 = TestProbe()
    val probeAck2 = TestProbe()
    val res = createScanResult(1)
    val messages = SinkTransitionMessages(Nil, List(probeAck1.ref, probeAck2.ref),
      processingDone = false)
    val helper = new SinkActorTestHelper

    helper.stub(messages, ScanSinkUpdateServiceImpl.InitialState) {
      _.handleNewScanResult(res, testActor, BufferSize)
    }
      .post(res)
    probeAck1.expectMsg(ScanSinkActor.Ack)
    probeAck2.expectMsg(ScanSinkActor.Ack)
    helper.expectNoResults()
  }

  it should "handle the arrival of a medium information" in {
    val probeAck = TestProbe()
    val info = createMediumInfo(1)
    val results = List(createCombinedResult(1))
    val messages = SinkTransitionMessages(results, List(probeAck.ref), processingDone = false)
    val helper = new SinkActorTestHelper

    helper.stub(messages, ScanSinkUpdateServiceImpl.InitialState) {
      _.handleNewMediumInfo(info, testActor, BufferSize)
    }
      .post(info)
      .expectCombinedResults(results)
    probeAck.expectMsg(ScanSinkActor.Ack)
  }

  it should "correctly manage its internal state" in {
    val res = createScanResult(1)
    val info = createMediumInfo(1)
    val results = List(createCombinedResult(1))
    val state = ScanSinkUpdateServiceImpl.InitialState.copy(scanResults = List(res))
    val messages1 = SinkTransitionMessages(Nil, Nil, processingDone = false)
    val messages2 = SinkTransitionMessages(results, Nil, processingDone = false)
    val helper = new SinkActorTestHelper

    helper.stub(messages1, state) {
      _.handleNewScanResult(res, testActor, BufferSize)
    }
      .stub(messages2, ScanSinkUpdateServiceImpl.InitialState) {
        _.handleNewMediumInfo(info, testActor, BufferSize)
      }
      .post(res)
      .expectStateUpdate(ScanSinkUpdateServiceImpl.InitialState)
      .expectNoResults()
      .post(info)
      .expectStateUpdate(state)
      .expectCombinedResults(results)
  }

  it should "handle an ACK message from the management actor" in {
    val actor = TestProbe()
    val messages = SinkTransitionMessages(Nil, List(actor.ref), processingDone = false)
    val helper = new SinkActorTestHelper

    helper.stub(messages, ScanSinkUpdateServiceImpl.InitialState) {
      _.handleResultAck(BufferSize)
    }
      .postAckFromManager()
    actor.expectMsg(ScanSinkActor.Ack)
  }

  it should "ignore an ACK message from another sender" in {
    val results1 = List(createCombinedResult(1))
    val results2 = List(createCombinedResult(2))
    val res = createScanResult(3)
    val messages1 = SinkTransitionMessages(results1, Nil, processingDone = false)
    val messages2 = SinkTransitionMessages(results2, Nil, processingDone = false)
    val helper = new SinkActorTestHelper

    helper.stub(messages1, ScanSinkUpdateServiceImpl.InitialState) {
      _.handleResultAck(BufferSize)
    }
      .stub(messages2, ScanSinkUpdateServiceImpl.InitialState) {
        _.handleNewScanResult(res, testActor, BufferSize)
      }
      .post(ScanSinkActor.Ack)
      .post(res)
      .expectCombinedResults(results2)
  }

  it should "handle a message about completed scan results" in {
    val results = List(createCombinedResult(1))
    val messages = SinkTransitionMessages(results, Nil, processingDone = false)
    val helper = new SinkActorTestHelper

    helper.stub(messages, ScanSinkUpdateServiceImpl.InitialState) {
      _.handleScanResultsDone(BufferSize)
    }
      .post(ScanSinkActor.ScanResultsComplete)
      .expectCombinedResults(results)
  }

  it should "handle a message about completed media info" in {
    val results = List(createCombinedResult(1))
    val messages = SinkTransitionMessages(results, Nil, processingDone = false)
    val helper = new SinkActorTestHelper

    helper.stub(messages, ScanSinkUpdateServiceImpl.InitialState) {
      _.handleMediaInfoDone(BufferSize)
    }
      .post(ScanSinkActor.MediaInfoComplete)
      .expectCombinedResults(results)
  }

  it should "handle processing done flag" in {
    val messages = SinkTransitionMessages(Nil, Nil, processingDone = true)
    val helper = new SinkActorTestHelper

    helper.stub(messages, ScanSinkUpdateServiceImpl.InitialState) {
      _.handleResultAck(BufferSize)
    }
      .postAckFromManager()
      .expectPromiseCompleted()
      .expectSinkActorStopped()
  }

  it should "handle a stream failure message" in {
    val ex = new RuntimeException("Stream failure!")
    val helper = new SinkActorTestHelper

    helper.post(ScanSinkActor.StreamFailure(ex))
      .expectPromiseFailed(ex)
      .expectSinkActorStopped()
  }

  /**
    * A test helper class managing a test instance and its dependencies.
    */
  private class SinkActorTestHelper
    extends StateTestHelper[ScanSinkState, ScanSinkUpdateService] {
    /** Mock for the scan sink update service. */
    override val updateService: ScanSinkUpdateService = mock[ScanSinkUpdateService]

    /** Test probe for the media manager actor. */
    private val probeManager = TestProbe()

    /** The promise passed to the test actor. */
    private val promise = Promise[Unit]()

    /** The actor to be tested. */
    private val sinkActor = createSinkActor()

    /**
      * Sends the specified message to the test actor.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def post(msg: Any): SinkActorTestHelper = {
      sinkActor ! msg
      this
    }

    /**
      * Tests that a message with the specified results has been sent to the
      * manager actor.
      *
      * @param results a list with the expected results
      * @return this test helper
      */
    def expectCombinedResults(results: Iterable[CombinedMediaScanResult]):
    SinkActorTestHelper = {
      probeManager.expectMsg(ScanSinkActor.CombinedResults(results, SeqNo))
      this
    }

    /**
      * Checks that no message was sent to the manager actor.
      *
      * @return this test helper
      */
    def expectNoResults(): SinkActorTestHelper = {
      expectNoMessageToProbe(probeManager)
      this
    }

    /**
      * Simulates an ACK message from the management actor.
      *
      * @return this test helper
      */
    def postAckFromManager(): SinkActorTestHelper = {
      sinkActor.tell(ScanSinkActor.Ack, probeManager.ref)
      this
    }

    /**
      * Tests that the test actor has been stopped.
      *
      * @return this test helper
      */
    def expectSinkActorStopped(): SinkActorTestHelper = {
      val probeWatcher = TestProbe()
      probeWatcher watch sinkActor
      probeWatcher.expectMsgType[Terminated]
      this
    }

    /**
      * Checks whether the promise passed to the test actor has been completed
      * with a success value.
      *
      * @return this test helper
      */
    def expectPromiseCompleted(): SinkActorTestHelper = {
      Await.result(promise.future, 5.seconds)
      this
    }

    /**
      * Checks whether the promise passed to the test actor has been completed
      * with the exception specified.
      *
      * @param ex the exception
      * @return this test helper
      */
    def expectPromiseFailed(ex: Throwable): SinkActorTestHelper = {
      import system.dispatcher
      val refEx = new AtomicReference[Throwable]
      promise.future.onComplete {
        case Failure(e) =>
          refEx set e
        case _ =>
      }
      awaitCond(refEx.get() == ex)
      this
    }

    /**
      * Creates a test actor instance.
      *
      * @return the test actor instance
      */
    private def createSinkActor(): ActorRef =
      system.actorOf(Props(new ScanSinkActor(probeManager.ref, promise, BufferSize, SeqNo,
        updateService)))
  }

}
