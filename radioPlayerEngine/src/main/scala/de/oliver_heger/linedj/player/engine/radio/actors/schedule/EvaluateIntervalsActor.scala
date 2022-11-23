/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.radio.actors.schedule

import akka.actor.Actor
import akka.pattern.pipe
import de.oliver_heger.linedj.player.engine.interval.IntervalQueries
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{IntervalQuery, IntervalQueryResult}
import de.oliver_heger.linedj.player.engine.radio.RadioSource
import de.oliver_heger.linedj.player.engine.radio.actors.schedule.EvaluateIntervalsActor.{EvaluateReplacementSources, EvaluateReplacementSourcesResponse, EvaluateSource, EvaluateSourceResponse}

import java.time.LocalDateTime
import scala.concurrent.Future

object EvaluateIntervalsActor {

  /**
    * A message to be processed by [[EvaluateIntervalsActor]] telling it to
    * evaluate the queries for a single source.
    *
    * @param source  the radio source
    * @param refDate the reference date for query evaluation
    * @param queries a sequence with the interval queries of this source
    * @param stateCount an internal counter; this is used to detect outdated
    *                   messages that are received after state changes
    * @param exclusions a set with radio sources to be excluded
    */
  private[schedule] case class EvaluateSource(source: RadioSource, refDate: LocalDateTime,
                                              queries: Seq[IntervalQuery], stateCount: Int = 0,
                                              exclusions: Set[RadioSource] = Set.empty)

  /**
    * A response message sent by [[EvaluateIntervalsActor]] in reaction on an
    * [[EvaluateSource]] message.
    *
    * @param result  the result of the evaluation
    * @param request the request this response is about
    */
  private[schedule] case class EvaluateSourceResponse(result: IntervalQueryResult, request:
  EvaluateSource)

  /**
    * A message to be processed by [[EvaluateIntervalsActor]] telling it to
    * evaluate the queries of all potential replacement sources. This request
    * is sent if the current radio source reaches a forbidden interval, and an
    * alternative has to be found. Therefore, the actor processes all queries
    * of all sources and returns a list with their results regarding the
    * reference date (which can be obtained from the passed in response for
    * the [[EvaluateSource]] message for the current source).
    *
    * @param sources               a map with all sources and their interval queries
    * @param currentSourceResponse results of the processing of the current source
    */
  private[schedule] case class EvaluateReplacementSources(sources: Map[RadioSource,
    Seq[IntervalQuery]], currentSourceResponse: EvaluateSourceResponse)

  /**
    * A response message sent by [[EvaluateIntervalsActor]] in reaction on an
    * [[EvaluateReplacementSources]] message. The response mainly consists of
    * an unordered list of the ''longest inside'' results of all processed
    * radio sources.
    *
    * @param results a sequence with the results of the evaluations
    * @param request the request this response is about
    */
  private[schedule] case class EvaluateReplacementSourcesResponse(results: Seq[(RadioSource,
    IntervalQueryResult)], request: EvaluateReplacementSources)

}

/**
  * An actor class that implements functionality related to the evaluation of
  * temporal interval queries.
  *
  * A radio source may be associated with a number of exclusion intervals, i.e.
  * temporal ranges in which this source must not be played. In order to find
  * out when the current source is allowed to be played, all intervals
  * associated with it have to be evaluated. If a replacement source has to be
  * found, it is even necessary to evaluate the intervals of all sources as
  * well. This is done by this actor class.
  *
  * The actor operates on futures to make sure that evaluation of interval
  * queries is done in parallel.
  */
class EvaluateIntervalsActor extends Actor {

  import context.dispatcher

  override def receive: Receive = {
    case src: EvaluateSource =>
      evaluateQueries(src.queries, src.refDate) map { r =>
        EvaluateSourceResponse(r, src)
      } pipeTo sender()

    case req: EvaluateReplacementSources =>
      val sr = req.currentSourceResponse.request
      Future.sequence(req.sources.toList filterNot { t =>
        t._1 == sr.source || sr.exclusions.contains(t._1)
      } map { t =>
        evaluateQueries(t._2, sr.refDate)
          .map(r => (t._1, r))
      }) map { seq =>
        EvaluateReplacementSourcesResponse(seq, req)
      } pipeTo sender()
  }

  /**
    * Creates a ''Future'' that evaluates the given interval queries based on
    * the specified reference date. All queries are executed, and a result is
    * selected using the ''longest inside'' selector.
    *
    * @param queries the queries to be evaluated
    * @param refDate the reference date
    * @return a ''Future'' with the result of the evaluation
    */
  private def evaluateQueries(queries: Seq[IntervalQuery], refDate: LocalDateTime)
  : Future[IntervalQueryResult] =
    Future.sequence(queries map (q => Future {
      q(refDate)
    })) map { results =>
      IntervalQueries.selectResult(results,
        IntervalQueries.LongestInsideSelector) getOrElse IntervalQueries.BeforeForEver
    }
}
