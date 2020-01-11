/*
 * Copyright 2015-2020 The Developers Team.
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

import java.time.{LocalDateTime, Month}

import de.oliver_heger.linedj.player.engine.RadioSource
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{After, Before, Inside,
IntervalQueryResult}
import de.oliver_heger.linedj.player.engine.interval.LazyDate
import org.scalatest.{FlatSpec, Matchers}

object ReplacementSourceSelectionStrategySpec {
  /**
    * Creates a radio source to be used in tests.
    *
    * @param idx the index of the source
    * @return the radio source with this index
    */
  private def radioSource(idx: Int): RadioSource =
    RadioSource("TestRadioSource_" + idx)
}

/**
  * Test class for ''ReplacementSourceSelectionStrategy''.
  */
class ReplacementSourceSelectionStrategySpec extends FlatSpec with Matchers {

  import ReplacementSourceSelectionStrategySpec._

  "A strategy" should "return a best fitting replacement source" in {
    val until = LocalDateTime.of(2016, Month.JUNE, 24, 20, 30)
    val bestFittingResult = After(identity[LocalDateTime])
    val bestFittingSource = radioSource(1)
    val sources = List((radioSource(3),
      Inside(new LazyDate(LocalDateTime.of(2016, Month.JUNE, 24, 21, 0)))),
      (bestFittingSource, bestFittingResult),
      (radioSource(2), Before(new LazyDate(LocalDateTime.of(2016, Month.JUNE, 24, 20, 25)))))
    val strategy = new ReplacementSourceSelectionStrategy

    val replacement = strategy.findReplacementSource(sources, until, RadioSource.NoRanking).get
    replacement.source should be(bestFittingSource)
    replacement.untilDate should be(until)
  }

  it should "return None for an empty list of replacements" in {
    val sources = List.empty[(RadioSource, IntervalQueryResult)]
    val strategy = new ReplacementSourceSelectionStrategy

    strategy.findReplacementSource(sources, LocalDateTime.now(),
      RadioSource.NoRanking) shouldBe 'empty
  }

  it should "return None if the best replacement is worse than the original source" in {
    val until = LocalDateTime.of(2016, Month.JUNE, 25, 17, 46, 1)
    val sources = List((radioSource(1), Inside(new LazyDate(until plusMinutes 5))),
      (radioSource(2), Inside(new LazyDate(until plusSeconds 1))))
    val strategy = new ReplacementSourceSelectionStrategy

    strategy.findReplacementSource(sources, until, RadioSource.NoRanking) shouldBe 'empty
  }

  it should "return an Inside result with a shorter until date" in {
    val until = LocalDateTime.of(2016, Month.JUNE, 25, 17, 53, 38)
    val sources = List((radioSource(1), Inside(new LazyDate(until plusMinutes 5))),
      (radioSource(2), Inside(new LazyDate(until plusSeconds -1))))
    val strategy = new ReplacementSourceSelectionStrategy

    val replacement = strategy.findReplacementSource(sources, until, RadioSource.NoRanking)
    replacement.get.source should be(radioSource(2))
    replacement.get.untilDate should be(until)
  }

  it should "return the start date of a Before result that starts earlier" in {
    val until = LocalDateTime.of(2016, Month.JUNE, 25, 18, 0, 0)
    val startBefore = until plusSeconds -1
    val sources = List((radioSource(1), Inside(new LazyDate(until plusMinutes 5))),
      (radioSource(2), Before(new LazyDate(startBefore))))
    val strategy = new ReplacementSourceSelectionStrategy

    val replacement = strategy.findReplacementSource(sources, until, RadioSource.NoRanking)
    replacement.get.source should be(radioSource(2))
    replacement.get.untilDate should be(startBefore)
  }

  it should "select a random source from the list of full replacements" in {
    val until = LocalDateTime.of(2016, Month.JUNE, 25, 18, 5, 24)
    val rep1 = radioSource(1)
    val rep2 = radioSource(2)
    val rep3 = radioSource(3)
    val sources = List((rep1, Before(new LazyDate(until))),
      (radioSource(4), Inside(new LazyDate(until))),
      (rep2, Before(new LazyDate(until plusDays 1))),
      (radioSource(5), Before(new LazyDate(until plusSeconds -1))),
      (rep3, Before(new LazyDate(until plusMinutes 1))))
    val strategy = new ReplacementSourceSelectionStrategy

    val results = (1 to 100).foldLeft(Set.empty[RadioSource])((s, _) => {
      val replacement = strategy.findReplacementSource(sources, until, RadioSource.NoRanking)
      replacement.get.untilDate should be(until)
      s + replacement.get.source
    })
    results should be(Set(rep1, rep2, rep3))
  }

  it should "apply the source ranking on the sources filling the gap" in {
    val until = LocalDateTime.of(2016, Month.JULY, 30, 15, 58, 40)
    val rep1 = radioSource(1)
    val rep2 = radioSource(2)
    val rep3 = radioSource(3)
    val sources = List((rep1, Before(new LazyDate(until))),
      (radioSource(4), Inside(new LazyDate(until))),
      (rep2, Before(new LazyDate(until plusDays 1))),
      (radioSource(5), Before(new LazyDate(until plusSeconds -1))),
      (rep3, Before(new LazyDate(until plusMinutes 1))))
    val ranking: RadioSource.Ranking = s =>
      if(s == rep2) 1 else 0
    val strategy = new ReplacementSourceSelectionStrategy

    val replacement = strategy.findReplacementSource(sources, until, ranking)
    replacement.get.untilDate should be(until)
    replacement.get.source should be(rep2)
  }
}
