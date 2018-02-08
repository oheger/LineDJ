/*
 * Copyright 2015-2018 The Developers Team.
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
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.RadioSource
import de.oliver_heger.linedj.player.engine.impl.schedule.EvaluateIntervalsActor.EvaluateReplacementSourcesResponse
import de.oliver_heger.linedj.player.engine.interval.IntervalQueries
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{After, Before, Inside, IntervalQuery}
import de.oliver_heger.linedj.utils.{ChildActorFactory, SchedulerSupport}

import scala.concurrent.duration._

object RadioSchedulerActor {
  /** The maximum future delay for scheduling a check for the current source. */
  private val MaximumFutureDelay = Duration ofDays 1

  /**
    * A message processed by [[RadioSchedulerActor]] that defines the exclusion
    * intervals for radio sources. The contained map associates the radio
    * sources with a a sequence of queries defining when this source should not
    * be played.
    *
    * @param sourceQueries a map with information about radio sources
    * @param rankingFunc   an optional ranking function for sources
    */
  case class RadioSourceData(sourceQueries: Map[RadioSource, Seq[IntervalQuery]],
                             rankingFunc: RadioSource.Ranking = RadioSource.NoRanking)

  /**
    * A message processed by [[RadioSchedulerActor]] that forces a new check of
    * the current source. The actor will re-evaluate all interval queries to
    * find a replacement source if necessary. The passed in exclusion sources
    * are not taken into account. This is useful if playback of a replacement
    * source caused an error, and now a replacement for the replacement source
    * has to be found.
    *
    * @param exclusions a set of sources to be excluded
    */
  case class CheckCurrentSource(exclusions: Set[RadioSource])

  /**
    * An internal message used by this actor class to trigger a check for the
    * current source. For the current source it is evaluated how long it can be
    * played. Then an evaluation has to be started to find a replacement
    * source. To achieve this, the scheduler is configured to send this message
    * at the correct point of time.
    *
    * @param state a code representing the current state of the actor; this is
    *              used to prevent that outdated messages are processed
    */
  private[schedule] case class CheckSchedule(state: Int)

  private class RadioSchedulerActorImpl(sourceActor: ActorRef)
    extends RadioSchedulerActor(sourceActor) with ChildActorFactory with SchedulerSupport

  /**
    * Creates a ''Props'' object for the creation of a new
    * ''RadioSchedulerActor'' instance.
    *
    * @param sourceActor the actor for reading radio sources
    * @return properties for creating a new actor instance
    */
  def apply(sourceActor: ActorRef): Props =
    Props(classOf[RadioSchedulerActorImpl], sourceActor)

  /**
    * Calculates an initial delay for a scheduler invocation based on the given
    * dates. The difference between these dates is calculated, but the delay
    * cannot become arbitrarily large.
    *
    * @param refDate the reference date
    * @param at      the date when a new check is to be scheduled
    * @return the initial delay for the scheduler invocation
    */
  private def calcScheduleDelay(refDate: LocalDateTime, at: LocalDateTime): FiniteDuration = {
    val delta = Duration.between(refDate, at)
    val delayDuration = if (delta.compareTo(MaximumFutureDelay) > 0) MaximumFutureDelay
    else delta
    FiniteDuration(delayDuration.toMillis, TimeUnit.MILLISECONDS)
  }
}

/**
  * An actor class providing scheduler support for the radio player.
  *
  * In the configuration of radio sources time slots can be specified in which
  * this source should not be played. This actor is responsible for enforcing
  * such rules.
  *
  * Switches to sources triggered by the user are routed through this actor.
  * That way the current source can be tracked, and its list of exclusions can
  * be evaluated. The system scheduler is used to intercept when a forbidden
  * time slot is reached. The actor then tries to determine a replacement
  * source that can be played in the meantime.
  *
  * @param sourceActor       the actor for reading the current radio source
  * @param selectionStrategy the replacement source selection strategy
  */
class RadioSchedulerActor(sourceActor: ActorRef,
                          val selectionStrategy: ReplacementSourceSelectionStrategy) extends
  Actor with ActorLogging {
  me: ChildActorFactory with SchedulerSupport =>

  import RadioSchedulerActor._

  def this(sourceActor: ActorRef) = this(sourceActor, new ReplacementSourceSelectionStrategy)

  /** The actor for evaluating interval queries. */
  private var evaluateIntervalsActor: ActorRef = _

  /**
    * Stores information about radio sources and the interval queries defining
    * their exclusion intervals.
    */
  private var radioSourceQueries = Map.empty[RadioSource, Seq[IntervalQuery]]

  /**
    * Stores the current ranking function for radio sources. This is needed by
    * the replacement source selection strategy.
    */
  private var rankingFunc = RadioSource.NoRanking

  /** The current radio source. */
  private var currentSource: Option[RadioSource] = None

  /**
    * The source that is currently played. Note that this can be different
    * from the current source if a replacement source is played.
    */
  private var playedSource: RadioSource = _

  /**
    * Handle to the scheduled source check. This is used to cancel a
    * scheduled check if the state of the actor changes
    */
  private var cancellable: Option[Cancellable] = None

  /**
    * A counter for detecting outdated response messages. This counter is
    * updated whenever an internal state change happens. If there is a pending
    * response message that refers to an old state, it can be detected by a
    * comparison with the current counter value.
    */
  private var stateCounter = 0

  @scala.throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    evaluateIntervalsActor = createChildActor(Props[EvaluateIntervalsActor])
  }

  override def receive: Receive = {
    case RadioSourceData(data, r) =>
      stateChanged()
      radioSourceQueries = data
      rankingFunc = r
      currentSource foreach (src => triggerSourceEval(src))

    case src: RadioSource =>
      stateChanged()
      currentSource = Some(src)
      playedSource = null  // force a restart of the source
      if (!triggerSourceEval(src)) {
        playSource(src)
      }

    case CheckSchedule(state) if validState(state) =>
      checkCurrentSource(Set.empty)

    case CheckCurrentSource(exclusions) =>
      stateChanged()
      checkCurrentSource(exclusions)

    case resp: EvaluateIntervalsActor.EvaluateSourceResponse if validState(
      resp.request.stateCount) =>
      resp.result match {
        case r@Before(_) =>
          handleBeforeCheckResult(resp, r)

        case After(_) =>
          handleBeforeCheckResult(resp, IntervalQueries.BeforeForEver)

        case Inside(_) =>
          log.info("Current source should not be played. Searching a replacement.")
          evaluateIntervalsActor ! EvaluateIntervalsActor.EvaluateReplacementSources(radioSourceQueries, resp)
      }

    case resp: EvaluateIntervalsActor.EvaluateReplacementSourcesResponse if validState(resp
      .request.currentSourceResponse.request.stateCount) =>
      val untilDate = fetchUntilDate(resp)
      val (src, date) = selectionStrategy.findReplacementSource(resp.results, untilDate,
        rankingFunc) match {
        case Some(repSel) => (repSel.source, repSel.untilDate)
        case None => (resp.request.currentSourceResponse.request.source, untilDate)
      }
      playSource(src)
      scheduleCheckAt(resp.request.currentSourceResponse, date)

    case CloseRequest =>
      stateChanged()
      sender ! CloseAck(self)
  }

  /**
    * Performs a check whether the current source (if defined) can be played
    * now. If this is not the case, a replacement source has to be found.
    *
    * @param exclusions sources to be excluded
    */
  private def checkCurrentSource(exclusions: Set[RadioSource]): Unit = {
    log.info("Checking current radio source.")
    currentSource foreach (triggerSourceEval(_, exclusions))
    cancellable = None
  }

  /**
    * The state of the actor has changed. This has to be recorded so that
    * outdated response messages can be detected.
    */
  private def stateChanged(): Unit = {
    cancellable foreach (_.cancel())
    cancellable = None
    stateCounter += 1
  }

  /**
    * Checks whether the given state counter refers to the current state.
    * Messages that do not pass this test are outdated and will be ignored.
    *
    * @param state the state counter
    * @return a flag whether this counter value is valid
    */
  private def validState(state: Int): Boolean = state == stateCounter

  /**
    * Sends a request to evaluate the specified source if necessary. Result is
    * '''true''' if such a request was sent. If the source has no associated
    * interval queries, no request is sent, and result is '''false'''.
    *
    * @param src        the source in question
    * @param exclusions sources to be excluded
    * @return a flag whether a request was sent
    */
  private def triggerSourceEval(src: RadioSource,
                                exclusions: Set[RadioSource] = Set.empty): Boolean = {
    val queries = radioSourceQueries.getOrElse(src, List.empty)
    if (queries.nonEmpty) {
      evaluateIntervalsActor ! EvaluateIntervalsActor.EvaluateSource(src, LocalDateTime.now(),
        queries, stateCounter, exclusions)
      true
    } else false
  }

  /**
    * Handles the result of a source evaluation if it is a Before.
    *
    * @param resp   the response message of the evaluation
    * @param result the ''Before'' result
    */
  private def handleBeforeCheckResult(resp: EvaluateIntervalsActor.EvaluateSourceResponse,
                                      result: Before): Unit = {
    scheduleCheckAt(resp, result.start.value)
    playSource(resp.request.source)
  }

  /**
    * Ensures that the specified source is played. A message is sent to the
    * source actor only if currently a different source is played.
    *
    * @param source the new source to be played
    */
  private def playSource(source: RadioSource): Unit = {
    if (playedSource != source) {
      log.info("Switch to source {}.", source.uri)
      sourceActor ! source
      playedSource = source
    }
  }

  /**
    * Obtains the until date from the given response message. Responses of this
    * type are produced only for ''Inside'' results; the until date has to be
    * obtained from this result.
    *
    * @param resp the response object
    * @return the until date obtained from the response
    */
  private def fetchUntilDate(resp: EvaluateReplacementSourcesResponse): LocalDateTime = {
    val result = resp.request.currentSourceResponse.result
    assert(result.isInstanceOf[Inside])
    result.asInstanceOf[Inside].until.value
  }

  /**
    * Creates a schedule to check the current source again at the specified
    * point of time.
    *
    * @param resp the source evaluation response message
    * @param date the date when to check again
    * @return the ''Cancellable'' pointing to the scheduled check
    */
  private def scheduleCheckAt(resp: EvaluateIntervalsActor.EvaluateSourceResponse, date:
  LocalDateTime): Unit = {
    val delay = calcScheduleDelay(resp.request.refDate, date)
    cancellable = Some(scheduleMessageOnce(delay, self,
      CheckSchedule(resp.request.stateCount)))
    log.info("Next check for current source in {} s.", delay.toSeconds)
  }
}
