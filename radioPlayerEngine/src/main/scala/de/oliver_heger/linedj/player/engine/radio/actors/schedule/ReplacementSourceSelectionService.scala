/*
 * Copyright 2015-2023 The Developers Team.
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

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{After, Before, Inside, IntervalQueryResult}
import de.oliver_heger.linedj.player.engine.radio.actors.schedule.ReplacementSourceSelectionService.SelectedReplacementSource
import de.oliver_heger.linedj.player.engine.radio.{RadioSource, RadioSourceConfig}

import java.time.LocalDateTime
import scala.collection.immutable.SortedMap
import scala.concurrent.Future
import scala.util.Random

object ReplacementSourceSelectionService {
  /**
    * A case class representing the selection of a replacement radio source. An
    * instance contains the source to be played as replacement and the date how
    * long this source can be played.
    *
    * @param source    the replacement radio source
    * @param untilDate the date until which this source can be played
    */
  case class SelectedReplacementSource(source: RadioSource, untilDate: LocalDateTime)
}

/**
  * A trait defining a service that selects a replacement source from a given
  * set of sources.
  *
  * The selection is done based on the exclusion intervals defined for radio
  * sources; sources not allowed to be played currently are avoided as far as
  * possible. In addition, a set of sources to exclude can be provided. This
  * can be used to model different exclusion criteria not based on concrete
  * time intervals.
  */
trait ReplacementSourceSelectionService {
  /**
    * Tries to find a replacement source based on the given parameters.
    *
    * @param sourcesConfig   the configuration with the available sources
    * @param rankedSources   a map grouping radio sources by their ranking
    * @param excludedSources a set with sources to ignore
    * @param untilDate       the date until when the replacement is needed
    * @param evaluateService the service to evaluate interval queries
    * @param system          the actor system
    * @return an ''Option'' with the result of the selection process
    */
  def selectReplacementSource(sourcesConfig: RadioSourceConfig,
                              rankedSources: SortedMap[Int, Seq[RadioSource]],
                              excludedSources: Set[RadioSource],
                              untilDate: LocalDateTime,
                              evaluateService: EvaluateIntervalsService)
                             (implicit system: ActorSystem): Future[Option[SelectedReplacementSource]]
}

/**
  * The default implementation of [[ReplacementSourceSelectionService]].
  *
  * This implementation tries to find a random radio source with a possible
  * high ranking that can be played the full time until the original source
  * becomes available again. If no such source can be found, a random source
  * with the highest ranking is searched that partly bridges the required time.
  * If this fails, too, result is ''None''.
  */
object ReplacementSourceSelectionServiceImpl extends ReplacementSourceSelectionService {
  /**
    * A source of randomness used to select an arbitrary radio source if there
    * are multiple with the same ranking.
    */
  private val random = new Random()

  override def selectReplacementSource(sourcesConfig: RadioSourceConfig,
                                       rankedSources: SortedMap[Int, Seq[RadioSource]],
                                       excludedSources: Set[RadioSource],
                                       untilDate: LocalDateTime,
                                       evaluateService: EvaluateIntervalsService)
                                      (implicit system: ActorSystem): Future[Option[SelectedReplacementSource]] = {
    import system.dispatcher

    // Look for full replacement sources.
    val futFullReplacement = runSelectionStream(sourcesConfig,
      rankedSources,
      excludedSources,
      untilDate,
      evaluateService) { (source, queryResult) =>
      (queryResult match {
        case After(_) => Some(source)
        case Before(d) if d.value.isAfter(untilDate) || d.value == untilDate =>
          Some(source)
        case _ => None
      }) map { selected => SelectedReplacementSource(selected, untilDate) }
    }

    // If no full replacement was found, also accept partial replacements.
    // Currently all partial replacements are accepted, no further evaluation
    // is done (e.g. to find one with a small forbidden range).
    // TODO: The current implementation will re-evaluate interval queries
    //  again; this should be prevented by reusing the results of the first
    //  evaluation.
    futFullReplacement flatMap { optReplacement =>
      optReplacement.map(replacement => Future.successful(Some(replacement))) getOrElse {
        runSelectionStream(sourcesConfig,
          rankedSources,
          excludedSources,
          untilDate,
          evaluateService) { (source, queryResult) =>
          queryResult match {
            case Inside(d) if d.value.isAfter(untilDate) => None
            case Before(start) => Some(SelectedReplacementSource(source, start.value))
            case _ => Some(SelectedReplacementSource(source, untilDate))
          }
        }
      }
    }
  }

  /**
    * Runs a stream to generate [[SelectedReplacementSource]] object using the
    * given parameters. A function is provided that determines whether a source
    * is selectable and how it should be represented as a selection result.
    *
    * @param sourcesConfig   the configuration of radio sources
    * @param rankedSources   a map with radio sources ordered by their ranking
    * @param excludedSources a set with sources to ignore
    * @param untilDate       the until date when to play the source
    * @param evaluateService the service to evaluate interval queries
    * @param resultFunc      the function to generate result objects
    * @param system          the actor system
    * @return a future with the optional selected source
    */
  private def runSelectionStream(sourcesConfig: RadioSourceConfig,
                                 rankedSources: SortedMap[Int, Seq[RadioSource]],
                                 excludedSources: Set[RadioSource],
                                 untilDate: LocalDateTime,
                                 evaluateService: EvaluateIntervalsService)
                                (resultFunc: (RadioSource, IntervalQueryResult) => Option[SelectedReplacementSource])
                                (implicit system: ActorSystem): Future[Option[SelectedReplacementSource]] = {
    import system.dispatcher
    val streamSource = Source(rankedSources.values.toSeq)
    val sink = Sink.headOption[SelectedReplacementSource]
    streamSource.map(sources => sources filterNot excludedSources.contains)
      .mapConcat(sources => random.shuffle(sources))
      .mapAsync(4) { src =>
        evaluateService.evaluateIntervals(sourcesConfig.exclusions(src), untilDate) map {
          (src, _)
        }
      }.map { t => resultFunc(t._1, t._2) }
      .filter(_.isDefined)
      .map(_.get)
      .runWith(sink)
  }
}
