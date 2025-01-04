/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.radio.control

import de.oliver_heger.linedj.player.engine.interval.IntervalQueries
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{IntervalQuery, IntervalQueryResult}
import de.oliver_heger.linedj.player.engine.radio.control.EvaluateIntervalsService.EvaluateIntervalsResponse

import java.time.LocalDateTime
import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}

object EvaluateIntervalsService:
  /**
    * A data class describing the response of [[EvaluateIntervalsService]] for
    * a request to evaluate a sequence of interval queries. In addition to the
    * actual result, the sequence number passed to the request is contained,
    * too. Thus it is possible to detect stale responses.
    *
    * @param result the result of the evaluation
    * @param seqNo  the sequence number that was part of the request
    */
  case class EvaluateIntervalsResponse(result: IntervalQueryResult, seqNo: Int)

/**
  * A trait defining a service that provides the efficient evaluation of
  * interval queries.
  *
  * This is used to check the exclusion queries of radio sources. That way it
  * can be determined whether a specific source can be played currently or at
  * a given reference date.
  */
trait EvaluateIntervalsService:
  /**
    * Returns a ''Future'' that evaluates the given interval queries based on
    * the specified reference date. All queries are executed, and a result is
    * selected using the ''longest inside'' selector.
    *
    * @param queries the queries to be evaluated
    * @param refDate the reference date
    * @param seqNo   a sequence number to be copied to the result
    * @param ec      the execution context
    * @return a ''Future'' with the result of the evaluation
    */
  def evaluateIntervals(queries: Seq[IntervalQuery], refDate: LocalDateTime, seqNo: Int)
                       (implicit ec: ExecutionContext): Future[EvaluateIntervalsResponse]

/**
  * A default implementation of the [[EvaluateIntervalsService]] trait.
  */
object EvaluateIntervalsServiceImpl extends EvaluateIntervalsService:
  override def evaluateIntervals(queries: Seq[IntervalQuery], refDate: LocalDateTime, seqNo: Int)
                                (implicit ec: ExecutionContext): Future[EvaluateIntervalsResponse] =
    Future.sequence(queries map (q => Future {
      q(refDate)
    })) map { results =>
      val evalResult = IntervalQueries.selectResult(results,
        IntervalQueries.LongestInsideSelector) getOrElse IntervalQueries.BeforeForEver
      EvaluateIntervalsResponse(evalResult, seqNo)
    }
