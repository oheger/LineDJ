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

import de.oliver_heger.linedj.player.engine.radio.RadioSource.Ranking
import de.oliver_heger.linedj.player.engine.interval.IntervalQueries
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{After, Before, Inside, IntervalQueryResult}
import de.oliver_heger.linedj.player.engine.radio.RadioSource

import java.time.LocalDateTime
import scala.util.Random

/**
  * A case class representing the selection of a replacement radio source. An
  * instance contains the source to be played as replacement and the date how
  * long this source can be played.
  *
  * @param source    the replacement radio source
  * @param untilDate the date until which this source can be played
  */
case class ReplacementSourceSelection(source: RadioSource, untilDate: LocalDateTime)

/**
  * A strategy class for selecting a replacement radio source.
  *
  * The radio player checks the interval queries defined for the current radio
  * source to find the next point of time when this source must not be played
  * (e.g. because a commercial break begins). Then a replacement source has to
  * be found which is played until the break is over. This is the task of this
  * class.
  *
  * A ''ReplacementSourceSelectionStrategy'' is passed a list with the interval
  * results of all potential replacement sources and the date when playback can
  * switch back to the original source. It returns an option with the selected
  * replacement source. Result can be ''None'' if no source can be found that
  * is suitable as a replacement.
  *
  * This implementation prefers radio sources that completely fill the time
  * the original source must not be played. If there are multiple candidate
  * sources, the ranking is taken into account. If this still does not result
  * in a single source, a random source from the potential candidates is
  * selected.
  */
class ReplacementSourceSelectionStrategy {
  /** A random for selecting a source if there are multiple options. */
  private val random = new Random

  /**
    * Tries to find a replacement radio source based on the specified
    * parameters.
    *
    * @param replacements a list with data about replacement sources
    * @param untilDate    the date when the original source can be played again
    * @param rankingFunc  the function for ranking sources
    * @return an option with the replacement source selection
    */
  def findReplacementSource(replacements: Seq[(RadioSource, IntervalQueryResult)],
                            untilDate: LocalDateTime,
                            rankingFunc: RadioSource => Int):
  Option[ReplacementSourceSelection] = {
    val srcSorted = replacements.sortWith((e1, e2) => IntervalQueries.ShortestInside(e1._2, e2._2))
    val fullReplacements = findFullReplacementSources(srcSorted, untilDate)
    bestRankedFullReplacement(fullReplacements, untilDate,
      rankingFunc) orElse bestFittingSource(srcSorted, untilDate)
  }

  /**
    * Tries to find a replacement source from the sources that fully bridge the
    * affected time interval. If such sources exist, the ranking function is
    * applied on them. On the sources with the highest ranking a random source
    * is selected.
    *
    * @param fullReplacements sequence of candidate sources
    * @param untilDate        the until date
    * @param rankingFunc      the ranking function
    * @return an option with a replacement source
    */
  private def bestRankedFullReplacement(fullReplacements: Seq[(RadioSource,
    IntervalQueryResult)], untilDate: LocalDateTime, rankingFunc: Ranking):
  Option[ReplacementSourceSelection] =
  if (fullReplacements.nonEmpty) {
    val rankingGroups = fullReplacements.groupBy(e => rankingFunc(e._1))
    val rep = random.shuffle(rankingGroups(rankingGroups.keys.max)).head
    Some(ReplacementSourceSelection(rep._1, untilDate))
  } else None

  /**
    * Determines a best-fitting source from the given sorted list of sources.
    *
    * @param srcSorted the sorted sequence of all replacement sources
    * @param untilDate the until date
    * @return an optional result for the best-fitting source
    */
  private def bestFittingSource(srcSorted: Seq[(RadioSource, IntervalQueryResult)],
                                untilDate: LocalDateTime): Option[ReplacementSourceSelection] =
    srcSorted.headOption flatMap { e =>
      e._2 match {
        case Inside(d) if d.value.isAfter(untilDate) => None
        case Before(d) => Some(ReplacementSourceSelection(e._1, d.value))
        case _ => Some(ReplacementSourceSelection(e._1, untilDate))
      }
    }

  /**
    * Returns a sequence with radio sources that can be used as full
    * replacements of the current source. These sources bridge the full gap of
    * the current source.
    *
    * @param srcSorted the sorted sequence of all replacement sources
    * @param untilDate the until date
    * @return a sequence with full replacement sources
    */
  private def findFullReplacementSources(srcSorted: Seq[(RadioSource, IntervalQueryResult)],
                                         untilDate: LocalDateTime): Seq[(RadioSource,
    IntervalQueryResult)] =
    srcSorted takeWhile { t =>
      t._2 match {
        case After(_) => true
        case Before(d) if d.value.isAfter(untilDate) || d.value == untilDate =>
          true
        case _ => false
      }
    }
}
