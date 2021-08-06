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

package de.oliver_heger.linedj.player.engine.impl.schedule

import java.time.{Duration, LocalDateTime}
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.RecordingSchedulerSupport
import de.oliver_heger.linedj.RecordingSchedulerSupport.SchedulerInvocation
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.{PlayerEvent, RadioSource, RadioSourceReplacementEndEvent, RadioSourceReplacementStartEvent}
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes._
import de.oliver_heger.linedj.player.engine.interval.IntervalQueries._
import de.oliver_heger.linedj.player.engine.interval.LazyDate
import de.oliver_heger.linedj.player.engine.impl.schedule.EvaluateIntervalsActor.EvaluateReplacementSources
import de.oliver_heger.linedj.utils.{ChildActorFactory, SchedulerSupport}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

object RadioSchedulerActorSpec {
  /** A dummy message for tests that no message was sent to a test actor. */
  private val NoMessage = "NoMessageTest"

  /** The map with information about interval queries for radio sources. */
  private val RadioSourceQueries = createSourceQueriesMap()

  /** The sequence with results for replacement sources. */
  private val ReplacementResults = createReplacementResults()

  /**
    * Creates a test radio source object.
    *
    * @param idx the index of the radio source
    * @return the test radio source with this index
    */
  private def radioSource(idx: Int): RadioSource =
    RadioSource("TestRadioSource_" + idx)

  /**
    * Creates a message for a response of an evaluation of replacement sources.
    *
    * @param resp the response for the current source
    * @return the message
    */
  private def replacementResponse(resp: EvaluateIntervalsActor.EvaluateSourceResponse):
  EvaluateIntervalsActor.EvaluateReplacementSourcesResponse =
    EvaluateIntervalsActor.EvaluateReplacementSourcesResponse(ReplacementResults,
      EvaluateIntervalsActor.EvaluateReplacementSources(RadioSourceQueries, resp))

  /**
    * Creates the map with radio sources and their associated queries.
    *
    * @return the map with sources and queries
    */
  private def createSourceQueriesMap(): Map[RadioSource, Seq[IntervalQuery]] =
    Map(radioSource(1) -> List(hours(1, 2)),
      radioSource(2) -> List(hours(2, 3)),
      radioSource(3) -> List(hours(4, 5)))

  /**
    * Creates a list representing results of a replacement source evaluation.
    *
    * @return the list with results for replacement sources
    */
  private def createReplacementResults(): Seq[(RadioSource, IntervalQueryResult)] =
    List((radioSource(1), BeforeForEver), (radioSource(2), After(identity[LocalDateTime])))
}

/**
  * Test class for ''RadioSchedulerActor''.
  */
class RadioSchedulerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import RadioSchedulerActorSpec._

  def this() = this(ActorSystem("RadioSchedulerActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A RadioSchedulerActor" should "create a default replacement selection strategy" in {
    val props = RadioSchedulerActor(TestProbe().ref)
    val ref = TestActorRef[RadioSchedulerActor](props)

    ref.underlyingActor.selectionStrategy should not be null
  }

  it should "create correct properties" in {
    val sourceActorProbe = TestProbe()
    val props = RadioSchedulerActor(sourceActorProbe.ref)

    props.args should have size 1
    props.args.head should be(sourceActorProbe.ref)
    classOf[RadioSchedulerActor] isAssignableFrom props.actorClass() shouldBe true
    classOf[ChildActorFactory] isAssignableFrom props.actorClass() shouldBe true
    classOf[SchedulerSupport] isAssignableFrom props.actorClass() shouldBe true
  }

  it should "send a request to evaluate a new source" in {
    val src = radioSource(1)
    val helper = new RadioSchedulerActorTestHelper().sendSourceData()

    helper receive src
    val request = helper.expectNoReplacementEvent().expectSourceEvaluationForNow()
    request.source should be(src)
    request.queries should be(RadioSourceQueries(src))
  }

  it should "directly set a source without interval queries" in {
    val src = radioSource(42)
    val helper = new RadioSchedulerActorTestHelper().sendSourceData()

    helper receive src
    helper.expectNoSourceEvaluation()
  }

  it should "trigger a new evaluation if the radio source data changes" in {
    val src = radioSource(1)
    val helper = new RadioSchedulerActorTestHelper
    helper receive src

    helper.sendSourceData()
    val request = helper.expectSourceEvaluationForNow()
    request.source should be(src)
  }

  it should "skip an evaluation if radio source data changes and no queries exist" in {
    val helper = new RadioSchedulerActorTestHelper
    helper receive radioSource(42)

    helper.sendSourceData().expectNoSourceEvaluation()
  }

  it should "change the internal state count if a new source is set" in {
    val helper = new RadioSchedulerActorTestHelper().sendSourceData()
    helper receive radioSource(1)
    val request1 = helper.expectSourceEvaluation()

    helper receive radioSource(2)
    val request2 = helper.expectSourceEvaluation()
    request1.stateCount should not be request2.stateCount
  }

  it should "change the internal state count if the source data changes" in {
    val src = radioSource(1)
    val helper = new RadioSchedulerActorTestHelper
    helper receive src
    helper.expectNoSourceEvaluation().sendSourceData()
    val request1 = helper.expectSourceEvaluation()

    val updatedQueries = RadioSourceQueries + (radioSource(4) -> List(hours(21, 22)))
    helper receive RadioSchedulerActor.RadioSourceData(updatedQueries)
    val request2 = helper.expectSourceEvaluation()
    request1.stateCount should not be request2.stateCount
  }

  it should "process a CheckSchedule message" in {
    val src = radioSource(1)
    val helper = new RadioSchedulerActorTestHelper
    helper receive src
    val eval = helper.sendSourceData().expectSourceEvaluation()

    helper receive RadioSchedulerActor.CheckSchedule(eval.stateCount)
    val eval2 = helper.expectSourceEvaluationForNow()
    eval2.source should be(src)
    eval2.queries should be(RadioSourceQueries(src))
    eval2.stateCount should be(eval.stateCount)
    eval.exclusions shouldBe empty
  }

  it should "process a CheckCurrentSource message" in {
    val src = radioSource(1)
    val exclusions = Set(radioSource(6), radioSource(7))
    val helper = new RadioSchedulerActorTestHelper
    helper receive src
    val eval = helper.sendSourceData().expectSourceEvaluation()

    helper receive RadioSchedulerActor.CheckCurrentSource(exclusions)
    val eval2 = helper.expectSourceEvaluationForNow()
    eval2.source should be(src)
    eval2.queries should be(RadioSourceQueries(src))
    eval2.stateCount should not be eval.stateCount
    eval2.exclusions should be(exclusions)
  }

  it should "ignore a CheckSchedule message if there is no current source" in {
    val helper = new RadioSchedulerActorTestHelper().sendSourceData()

    helper receive RadioSchedulerActor.CheckSchedule(1)
    helper.expectNoSourceEvaluation()
  }

  it should "ignore an outdated CheckSchedule message" in {
    val helper = new RadioSchedulerActorTestHelper
    helper receive radioSource(2)
    helper.sendSourceData().expectSourceEvaluation()

    helper receive RadioSchedulerActor.CheckSchedule(0)
    helper.expectNoSourceEvaluation()
  }

  it should "handle a Before evaluation response" in {
    val helper = new RadioSchedulerActorTestHelper
    val source = radioSource(1)
    val resp = helper.handleSourceEvaluation(source, Before(new LazyDate(LocalDateTime.now()
      plusMinutes 1)))

    helper.expectSchedule(RadioSchedulerActor.CheckSchedule(resp.request.stateCount), 1.minute)
  }

  it should "set a schedule no longer than a day in the future" in {
    val helper = new RadioSchedulerActorTestHelper
    val resp = helper.handleSourceEvaluation(radioSource(1),
      Before(new LazyDate(LocalDateTime.now() plusYears 12)))

    helper.expectSchedule(RadioSchedulerActor.CheckSchedule(resp.request.stateCount), 1.day)
  }

  it should "handle an After evaluation response" in {
    val helper = new RadioSchedulerActorTestHelper
    val source = radioSource(1)
    val resp = helper.handleSourceEvaluation(source, After(identity[LocalDateTime]))

    helper.expectSchedule(RadioSchedulerActor.CheckSchedule(resp
      .request.stateCount), 1.day)
  }

  it should "ignore an outdated evaluation response" in {
    val helper = new RadioSchedulerActorTestHelper().sendSourceData()
    helper receive radioSource(1)
    val request = helper.expectSourceEvaluation()

    val resp = EvaluateIntervalsActor.EvaluateSourceResponse(After(identity[LocalDateTime]),
      request.copy(stateCount = request.stateCount - 1))
    helper receive resp
    helper.expectNoSchedule()
  }

  it should "not send the same radio source twice" in {
    val helper = new RadioSchedulerActorTestHelper
    val source = radioSource(1)
    val resp = helper.handleSourceEvaluation(source, Before(new LazyDate(LocalDateTime.now()
      plusMinutes 1)))

    helper receive resp
    helper.expectNoReplacementEvent()
  }

  it should "not send a replacement event if evaluation yields no forbidden timeslot" in {
    val helper = new RadioSchedulerActorTestHelper
    val source = radioSource(2)
    helper receive source
    val request = helper.sendSourceData().expectSourceEvaluation()
    val resp = EvaluateIntervalsActor.EvaluateSourceResponse(After(identity[LocalDateTime]),
      request)

    helper receive resp
    helper.expectNoReplacementEvent()
  }

  it should "directly set the current source when receiving a RadioSource message" in {
    val helper = new RadioSchedulerActorTestHelper().sendSourceData()
    helper receive radioSource(1)
    helper.expectSourceEvaluation()

    helper.sendSourceData()
    helper.expectSourceEvaluation()
  }

  it should "cancel a scheduled check if new source data is set" in {
    val helper = new RadioSchedulerActorTestHelper
    val resp = helper.handleSourceEvaluation(radioSource(2), BeforeForEver)
    val schedule = helper.expectSchedule(RadioSchedulerActor.CheckSchedule(resp.request
      .stateCount), 1.day)

    helper.sendSourceData()
    schedule.cancellable.isCancelled shouldBe true
  }

  it should "cancel a scheduled check if another source is set" in {
    val helper = new RadioSchedulerActorTestHelper
    val resp = helper.handleSourceEvaluation(radioSource(2), BeforeForEver)
    val schedule = helper.expectSchedule(RadioSchedulerActor.CheckSchedule(resp.request
      .stateCount), 1.day)

    helper receive radioSource(1)
    schedule.cancellable.isCancelled shouldBe true
  }

  it should "cancel a scheduled check if a check is forced" in {
    val helper = new RadioSchedulerActorTestHelper
    val resp = helper.handleSourceEvaluation(radioSource(2), BeforeForEver)
    val schedule = helper.expectSchedule(RadioSchedulerActor.CheckSchedule(resp.request
      .stateCount), 1.day)

    helper receive RadioSchedulerActor.CheckCurrentSource(Set.empty)
    schedule.cancellable.isCancelled shouldBe true
  }

  it should "reset the Cancellable after a schedule was cancelled" in {
    val helper = new RadioSchedulerActorTestHelper
    val resp = helper.handleSourceEvaluation(radioSource(2), BeforeForEver)
    val schedule = helper.expectSchedule(RadioSchedulerActor.CheckSchedule(resp.request
      .stateCount), 1.day)

    helper receive radioSource(1)
    helper.sendSourceData()
    schedule.cancellable.cancelCount should be(1)
  }

  it should "reset the Cancellable on receiving a CheckSchedule message" in {
    val helper = new RadioSchedulerActorTestHelper
    val resp = helper.handleSourceEvaluation(radioSource(2), BeforeForEver)
    val schedule = helper.expectSchedule(RadioSchedulerActor.CheckSchedule(resp.request
      .stateCount), 1.day)

    helper receive RadioSchedulerActor.CheckSchedule(resp.request.stateCount)
    helper.sendSourceData()
    schedule.cancellable.cancelCount should be(0)
  }

  it should "trigger the evaluation of all sources if the current one is Inside" in {
    val helper = new RadioSchedulerActorTestHelper
    val until = LocalDateTime.now() plusMinutes 5
    val resp = helper.handleSourceEvaluation(radioSource(2), Inside(new LazyDate(until)))

    helper.expectReplacementEvaluation(resp)
  }

  it should "send events about a replacement source" in {
    val helper = new RadioSchedulerActorTestHelper
    val until = LocalDateTime.now() plusMinutes 5
    val untilRepl = until plusMinutes -2
    val orgSrc = radioSource(2)
    val replSrc = radioSource(3)
    val resp = helper.handleSourceEvaluation(orgSrc, Inside(new LazyDate(until)))
    when(helper.selectionStrategy.findReplacementSource(ReplacementResults, until,
      RadioSource.NoRanking))
      .thenReturn(Some(ReplacementSourceSelection(replSrc, untilRepl)))

    helper.expectReplacementEvaluation(resp)
    helper receive replacementResponse(resp)
    helper.expectSchedule(RadioSchedulerActor.CheckSchedule(resp.request.stateCount), 3.minutes)
    helper.expectReplacementEvent(RadioSourceReplacementStartEvent(orgSrc, replSrc))

    helper receive RadioSchedulerActor.CheckSchedule(resp.request.stateCount)
    val request = helper.expectSourceEvaluation()
    val response = EvaluateIntervalsActor.EvaluateSourceResponse(After(identity[LocalDateTime]), request)
    helper receive response
    helper.expectReplacementEvent(RadioSourceReplacementEndEvent(orgSrc))
  }

  it should "not send a replacement event if no replacement can be found" in {
    val helper = new RadioSchedulerActorTestHelper
    val until = LocalDateTime.now() plusMinutes 5
    val source = radioSource(2)
    val resp = helper.handleSourceEvaluation(source, Inside(new LazyDate(until)))
    when(helper.selectionStrategy.findReplacementSource(ReplacementResults, until,
      RadioSource.NoRanking)).thenReturn(None)

    helper receive replacementResponse(resp)
    helper.expectNoReplacementEvent()
      .expectSchedule(RadioSchedulerActor.CheckSchedule(resp.request.stateCount), 5.minutes)
  }

  it should "pass the ranking function to the selection strategy" in {
    val helper = new RadioSchedulerActorTestHelper
    val until = LocalDateTime.now() plusHours 2
    val source = radioSource(3)
    val ranking: RadioSource.Ranking = s => s.uri.length
    val resp = helper.handleSourceEvaluation(source, Inside(new LazyDate(until)),
      r = ranking)
    when(helper.selectionStrategy.findReplacementSource(ReplacementResults, until,
      ranking)).thenReturn(None)

    helper receive replacementResponse(resp)
    verify(helper.selectionStrategy).findReplacementSource(ReplacementResults, until, ranking)
  }

  it should "ignore outdated replacement source messages" in {
    val helper = new RadioSchedulerActorTestHelper
    val resp = helper.handleSourceEvaluation(radioSource(1), Inside(new LazyDate(LocalDateTime
      .now())))
    helper.sendSourceData()

    helper receive replacementResponse(resp)
    verifyNoMoreInteractions(helper.selectionStrategy)
    helper.expectNoReplacementEvent().expectNoSchedule()
  }

  it should "cancel a pending schedule on receiving a close request" in {
    val helper = new RadioSchedulerActorTestHelper
    val resp = helper.handleSourceEvaluation(radioSource(1), BeforeForEver)
    val schedule = helper.expectSchedule(RadioSchedulerActor.CheckSchedule(resp.request
      .stateCount), 1.day)

    helper.actor ! CloseRequest
    expectMsg(CloseAck(helper.actor))
    schedule.cancellable.isCancelled shouldBe true
  }

  it should "ignore pending messages after a close request" in {
    val helper = new RadioSchedulerActorTestHelper().sendSourceData()
    helper receive radioSource(1)
    val eval = helper.expectSourceEvaluation()

    helper.actor ! CloseRequest
    expectMsg(CloseAck(helper.actor))
    helper receive EvaluateIntervalsActor.EvaluateSourceResponse(BeforeForEver, eval)
    helper.expectNoReplacementEvent()
  }

  /**
    * A test helper class managing the dependencies of a test instance.
    */
  private class RadioSchedulerActorTestHelper {
    /** Test probe for the event actor. */
    val eventActorProbe: TestProbe = TestProbe()

    /** Test probe for the evaluate actor. */
    val evaluateActorProbe: TestProbe = TestProbe()

    /** Mock for the selection strategy. */
    val selectionStrategy: ReplacementSourceSelectionStrategy = mock[ReplacementSourceSelectionStrategy]

    /** The queue which tracks scheduler invocations. */
    val scheduleQueue = new LinkedBlockingQueue[RecordingSchedulerSupport.SchedulerInvocation]

    /** The test actor instance. */
    val actor: TestActorRef[RadioSchedulerActor] = createTestActor()

    /**
      * Sends the specified message directly to the test actor.
      *
      * @param msg the message
      */
    def receive(msg: Any): Unit = {
      actor receive msg
    }

    /**
      * Sends a message with data about radio sources to the test actor.
      *
      * @param r an optional ranking function
      * @return this helper
      */
    def sendSourceData(r: RadioSource.Ranking = RadioSource.NoRanking):
    RadioSchedulerActorTestHelper = {
      receive(RadioSchedulerActor.RadioSourceData(RadioSourceQueries, r))
      this
    }

    /**
      * Expects an invocation of the scheduler and returns the passed data.
      *
      * @param expMsg   the expected message
      * @param expDelay the expected initial delay
      * @return the data of the scheduler invocation
      */
    def expectSchedule(expMsg: Any, expDelay: FiniteDuration): SchedulerInvocation = {
      val schedule = RecordingSchedulerSupport.expectInvocation(scheduleQueue)
      schedule.receiver should be(actor)
      schedule.interval should be(null)
      schedule.message should be(expMsg)
      math.abs((expDelay - schedule.initialDelay).toSeconds) should be < 3L
      schedule
    }

    /**
      * Expects that no schedule was created. Because the interaction with the
      * test actor is synchronous, it is sufficient to check whether the
      * schedule queue is empty.
      *
      * @return this helper
      */
    def expectNoSchedule(): RadioSchedulerActorTestHelper = {
      scheduleQueue.isEmpty shouldBe true
      this
    }

    /**
      * Handles messages for the evaluation of a source. The source is sent to
      * the test actor, an evaluation request is expected, and a response with
      * the given result is sent.
      *
      * @param source     the source
      * @param result     the result of the source evaluation
      * @param sendSource flag whether the source should be sent to the actor
      * @param r          an optional ranking function
      * @return the response message
      */
    def handleSourceEvaluation(source: RadioSource, result: IntervalQueryResult, sendSource: Boolean = true,
                               r: RadioSource.Ranking = RadioSource.NoRanking):
    EvaluateIntervalsActor.EvaluateSourceResponse = {
      sendSourceData(r)
      if (sendSource) {
        receive(source)
      }
      val request = expectSourceEvaluation()
      val response = EvaluateIntervalsActor.EvaluateSourceResponse(result, request)
      receive(response)
      response
    }

    /**
      * Expects a request to evaluate a source.
      *
      * @return the request message
      */
    def expectSourceEvaluation(): EvaluateIntervalsActor.EvaluateSource =
      evaluateActorProbe.expectMsgType[EvaluateIntervalsActor.EvaluateSource]

    /**
      * Expects a request to evaluate a source and checks the reference date.
      *
      * @return the request message
      */
    def expectSourceEvaluationForNow(): EvaluateIntervalsActor.EvaluateSource = {
      val req = expectSourceEvaluation()
      checkCurrentDate(req.refDate)
      req
    }

    /**
      * Expects a request to evaluate replacement sources.
      *
      * @param expSrcResp the expected source response
      * @return the received message
      */
    def expectReplacementEvaluation(expSrcResp: EvaluateIntervalsActor.EvaluateSourceResponse):
    EvaluateIntervalsActor.EvaluateReplacementSources = {
      val msg = evaluateActorProbe.expectMsgType[EvaluateReplacementSources]
      msg.sources should be(RadioSourceQueries)
      msg.currentSourceResponse should be(expSrcResp)
      msg
    }

    /**
      * Checks that no message was sent to the evaluation actor.
      *
      * @return this helper
      */
    def expectNoSourceEvaluation(): RadioSchedulerActorTestHelper =
      expectNoMsg(evaluateActorProbe)

    /**
      * Expects that the specified player event is fired.
      *
      * @param event the expected ''PlayerEvent''
      * @return this helper
      */
    def expectReplacementEvent(event: PlayerEvent): RadioSchedulerActorTestHelper = {
      val eventMsg = eventActorProbe.expectMsgType[PlayerEvent]
      checkCurrentDate(eventMsg.time)
      val eventWithTime = event match {
        case e: RadioSourceReplacementStartEvent =>
          e.copy(time = eventMsg.time)
        case e: RadioSourceReplacementEndEvent =>
          e.copy(time = eventMsg.time)
        case e => fail("Unexpected event: " + e)
      }
      eventMsg should be(eventWithTime)
      this
    }

    /**
      * Checks that no event regarding a replacement source is fired.
      *
      * @return this helper
      */
    def expectNoReplacementEvent(): RadioSchedulerActorTestHelper =
      expectNoMsg(eventActorProbe)

    /**
      * Helper method for testing that a test probe was not sent a message.
      *
      * @param probe the test probe
      * @return this helper
      */
    private def expectNoMsg(probe: TestProbe): RadioSchedulerActorTestHelper = {
      probe.ref ! NoMessage
      probe.expectMsg(NoMessage)
      this
    }

    /**
      * Checks that the passed in date is approximately the current date. The
      * actor needs to base query evaluations on the current date.
      *
      * @param dt the date to be checked
      * @return the same date
      */
    private def checkCurrentDate(dt: LocalDateTime): LocalDateTime = {
      val diff = Duration.between(LocalDateTime.now(), dt)
      diff.toMillis.toInt should be < 2000
      dt
    }

    /**
      * Creates a test actor instance.
      *
      * @return the test actor instance
      */
    private def createTestActor(): TestActorRef[RadioSchedulerActor] =
      TestActorRef[RadioSchedulerActor](createProps())

    /**
      * Creates the properties for a test actor instance. A child actor factory
      * is set which returns the test probe for the evaluation actor. The
      * scheduler support implementation tracks all scheduler invocations.
      *
      * @return ''Props'' for a test actor instance
      */
    private def createProps(): Props =
      Props(new RadioSchedulerActor(eventActorProbe.ref, selectionStrategy)
        with RecordingSchedulerSupport with ChildActorFactory {
        override val queue: BlockingQueue[SchedulerInvocation] = scheduleQueue

        override def createChildActor(p: Props): ActorRef = {
          p.actorClass() should be(classOf[EvaluateIntervalsActor])
          p.args shouldBe empty
          evaluateActorProbe.ref
        }
      })
  }

}
