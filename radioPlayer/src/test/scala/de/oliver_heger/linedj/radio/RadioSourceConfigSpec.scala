/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.radio

import java.time.{LocalDateTime, Month}

import de.oliver_heger.linedj.player.engine.RadioSource
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{Before, Inside, IntervalQuery}
import org.apache.commons.configuration.{Configuration, HierarchicalConfiguration}
import org.scalatest.{FlatSpec, Matchers}

object RadioSourceConfigSpec {
  /** Prefix for a radio source name. */
  private val RadioSourceName = "Radio_"

  /** Prefix for a radio source URI. */
  private val RadioSourceURI = "http://rad.io/"

  /**
    * Generates the name for the source with the given index.
    *
    * @param idx the index
    * @return the name for this radio source
    */
  private def sourceName(idx: Int): String = RadioSourceName + idx

  /**
    * Determines whether the radio source with the given index has a default
    * file extension. All sources with an odd index have.
    *
    * @param idx the index
    * @return a flag whether this source has a default extension
    */
  private def hasDefaultExt(idx: Int): Boolean = idx % 2 != 0

  /**
    * Generates a radio source object based on the given index.
    *
    * @param idx the index
    * @return the radio source for this index
    */
  private def radioSource(idx: Int): RadioSource =
    RadioSource(RadioSourceURI + idx, if (hasDefaultExt(idx)) Some("mp3") else None)

  /**
    * Produces a tuple of radio source data with ranking.
    *
    * @param idx     the index of the radio source
    * @param ranking the ranking
    * @return the tuple with the source name, the source, and the ranking
    */
  private def sourceWithRanking(idx: Int, ranking: Int): (String, RadioSource, Int) =
  (sourceName(idx), radioSource(idx), ranking)

  /**
    * Creates a configuration that contains the given number of radio sources.
    * All sources are defined with their name and URI. Sources with an odd
    * index have a default file extension.
    *
    * @param count the number of radio sources
    * @return the configuration defining the number of radio sources
    */
  private def createSourceConfiguration(count: Int): Configuration = {
    val sources = (1 to count) map (i => (sourceName(i), radioSource(i), -1))
    createSourceConfiguration(sources)
  }

  /**
    * Creates a configuration that contains the specified radio sources. The
    * passed in list contains tuples defining a radio source each: its name,
    * its URI, and its ranking. Ranking values less than zero are ignored and
    * not written into the configuration.
    *
    * @param sources a list with the sources to be contained (with ranking)
    * @return the configuration with these radio sources
    */
  private def createSourceConfiguration(sources: Seq[(String, RadioSource, Int)]): Configuration = {
    val config = new HierarchicalConfiguration
    sources foreach { t =>
      config.addProperty("radio.sources.source(-1).name", t._1)
      config.addProperty("radio.sources.source.uri", t._2.uri)
      if (t._3 > 0) {
        config.addProperty("radio.sources.source.ranking", t._3)
      }
      t._2.defaultExtension foreach (config.addProperty("radio.sources.source.extension", _))
    }
    config
  }

  /**
    * Generates the key for an exclusion for a radio source.
    *
    * @param srcIdx  the index of the source
    * @param suffix  the suffix of the exclusion key
    * @param exclIdx an optional index for the index of the exclusion element
    * @return the full key
    */
  private def exclusionKey(srcIdx: Int, suffix: String, exclIdx: Option[Int] = None): String =
    s"radio.sources.source($srcIdx).exclusions.exclusion${exclIdx.map(i => s"($i)").getOrElse("")
    }.$suffix"
}

/**
  * Test class for ''RadioSourceConfig''.
  */
class RadioSourceConfigSpec extends FlatSpec with Matchers {

  import RadioSourceConfigSpec._

  /**
    * Checks whether the specified interval query yields a Before result for
    * the given date with the provided start time.
    *
    * @param query the query
    * @param date  the reference date
    * @param start the start time
    */
  private def assertBefore(query: IntervalQuery, date: LocalDateTime, start: LocalDateTime): Unit
  = {
    query(date) match {
      case Before(d) => d.value should be(start)
      case r => fail("Unexpected result: " + r)
    }
  }

  /**
    * Checks whether the specified interval query yields an Inside result for
    * the given date with the provided until time.
    *
    * @param query the query
    * @param date  the reference date
    * @param until the until time
    */
  private def assertInside(query: IntervalQuery, date: LocalDateTime, until: LocalDateTime): Unit
  = {
    query(date) match {
      case Inside(d) => d.value should be(until)
      case r => fail("Unexpected result: " + r)
    }
  }

  "A RadioSourceConfig" should "provide a correct list of sources" in {
    val Count = 4
    val config = createSourceConfiguration(Count)

    val sourceConfig = RadioSourceConfig(config)
    val expSrcList = (1 to Count) map (i => (sourceName(i), radioSource(i)))
    sourceConfig.sources should be(expSrcList)
  }

  it should "ignore incomplete source definitions" in {
    val Count = 2
    val config = createSourceConfiguration(Count)
    config.addProperty("radio.sources.source(-1).name", "someName")
    config.addProperty("radio.sources.source(-1).uri", "someURI")

    val sourceConfig = RadioSourceConfig(config)
    sourceConfig.sources should have size 2
    sourceConfig.sources.head._1 should be(sourceName(1))
    sourceConfig.sources(1)._1 should be(sourceName(2))
  }

  it should "order radio sources by name" in {
    val sources = List((sourceName(8), radioSource(8)), (sourceName(2), radioSource(2)),
      (sourceName(3), radioSource(3)), (sourceName(9), radioSource(9)))
    val sourcesSorted = List((sourceName(2), radioSource(2)), (sourceName(3), radioSource(3)),
      (sourceName(8), radioSource(8)), (sourceName(9), radioSource(9)))
    val config = createSourceConfiguration(sources map (t => (t._1, t._2, -1)))

    val sourceConfig = RadioSourceConfig(config)
    sourceConfig.sources should be(sourcesSorted)
  }

  it should "order radio sources by ranking" in {
    val sources = List(sourceWithRanking(1, 1), sourceWithRanking(2, 17),
      sourceWithRanking(3, -1), sourceWithRanking(4, 8),
      sourceWithRanking(5, 1))
    val sourcesSorted = List((sourceName(2), radioSource(2)),
      (sourceName(4), radioSource(4)),
      (sourceName(1), radioSource(1)),
      (sourceName(5), radioSource(5)),
      (sourceName(3), radioSource(3)))
    val config = createSourceConfiguration(sources)

    val sourceConfig = RadioSourceConfig(config)
    sourceConfig.sources should be(sourcesSorted)
  }

  it should "return a map with all sources and empty lists if no exclusions are defined" in {
    val config = createSourceConfiguration(2)

    val sourceConfig = RadioSourceConfig(config)
    sourceConfig.exclusions should have size 2
    sourceConfig.exclusions.values.forall(_.isEmpty) shouldBe true
  }

  it should "process a minutes exclusion" in {
    val config = createSourceConfiguration(4)
    config.addProperty(exclusionKey(2, "minutes[@from]"), "22")
    config.addProperty(exclusionKey(2, "minutes[@to]"), 25)

    val sourceConfig = RadioSourceConfig(config)
    val exclusions = sourceConfig.exclusions(radioSource(3))
    exclusions should have size 1
    assertBefore(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 29, 19, 21),
      LocalDateTime.of(2016, Month.JUNE, 29, 19, 22))
    assertInside(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 30, 22, 23),
      LocalDateTime.of(2016, Month.JUNE, 30, 22, 25))
  }

  it should "drop an undefined exclusion" in {
    val config = createSourceConfiguration(1)
    config.addProperty(exclusionKey(0, "unsupported"), "1")

    val sourceConfig = RadioSourceConfig(config)
    sourceConfig.exclusions(radioSource(1)) shouldBe 'empty
  }

  it should "drop a minute exclusion with an invalid from" in {
    val config = createSourceConfiguration(1)
    config.addProperty(exclusionKey(0, "minutes[@from]"), "61")
    config.addProperty(exclusionKey(0, "minutes[@to]"), 52)

    val sourceConfig = RadioSourceConfig(config)
    sourceConfig.exclusions(radioSource(1)) shouldBe 'empty
  }

  it should "drop a minute exclusion with an invalid to" in {
    val config = createSourceConfiguration(1)
    config.addProperty(exclusionKey(0, "minutes[@from]"), "55")
    config.addProperty(exclusionKey(0, "minutes[@to]"), "-1")

    val sourceConfig = RadioSourceConfig(config)
    sourceConfig.exclusions(radioSource(1)) shouldBe 'empty
  }

  it should "ignore a minute exclusion with from >= to" in {
    val config = createSourceConfiguration(1)
    config.addProperty(exclusionKey(0, "minutes[@from]"), "58")
    config.addProperty(exclusionKey(0, "minutes[@to]"), "58")

    val sourceConfig = RadioSourceConfig(config)
    sourceConfig.exclusions(radioSource(1)) shouldBe 'empty
  }

  it should "ignore a minute exclusion with a non-string parameter" in {
    val config = createSourceConfiguration(1)
    config.addProperty(exclusionKey(0, "minutes[@from]"), "noNumber")
    config.addProperty(exclusionKey(0, "minutes[@to]"), "58")

    val sourceConfig = RadioSourceConfig(config)
    sourceConfig.exclusions(radioSource(1)) shouldBe 'empty
  }

  it should "combine multiple interval queries" in {
    val config = createSourceConfiguration(1)
    config.addProperty(exclusionKey(0, "minutes[@from]"), "14")
    config.addProperty(exclusionKey(0, "minutes[@to]"), "20")
    config.addProperty(exclusionKey(0, "hours[@from]"), "21")
    config.addProperty(exclusionKey(0, "hours[@to]"), "23")

    val sourceConfig = RadioSourceConfig(config)
    val exclusions = sourceConfig.exclusions(radioSource(1))
    exclusions should have size 1
    assertBefore(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 29, 20, 10),
      LocalDateTime.of(2016, Month.JUNE, 29, 21, 14))
    assertInside(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 30, 22, 14),
      LocalDateTime.of(2016, Month.JUNE, 30, 22, 20))
  }

  it should "ignore an hours exclusion with invalid values" in {
    val config = createSourceConfiguration(1)
    config.addProperty(exclusionKey(0, "hours[@from]"), "21")
    config.addProperty(exclusionKey(0, "hours[@to]"), "25")

    val sourceConfig = RadioSourceConfig(config)
    sourceConfig.exclusions(radioSource(1)) shouldBe 'empty
  }

  it should "parse a days-of-week query" in {
    val config = createSourceConfiguration(1)
    config.addProperty(exclusionKey(0, "days.day"), Array("MONDAY", "WEDNESDAY", "SATURDAY"))

    val sourceConfig = RadioSourceConfig(config)
    val exclusions = sourceConfig.exclusions(radioSource(1))
    exclusions should have size 1
    assertInside(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 27, 21, 29),
      LocalDateTime.of(2016, Month.JUNE, 28, 0, 0))
    assertInside(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 29, 21, 29),
      LocalDateTime.of(2016, Month.JUNE, 30, 0, 0))
    assertInside(exclusions.head, LocalDateTime.of(2016, Month.JULY, 2, 21, 29),
      LocalDateTime.of(2016, Month.JULY, 3, 0, 0))
    assertBefore(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 28, 21, 31),
      LocalDateTime.of(2016, Month.JUNE, 29, 0, 0))
    assertBefore(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 30, 21, 31),
      LocalDateTime.of(2016, Month.JULY, 2, 0, 0))
  }

  it should "ignore a days-of-week query with an invalid day" in {
    val config = createSourceConfiguration(1)
    config.addProperty(exclusionKey(0, "days.day"), Array("TUESDAY", "FRIDAY", "unknown day"))

    val sourceConfig = RadioSourceConfig(config)
    sourceConfig.exclusions(radioSource(1)) shouldBe 'empty
  }

  it should "create cyclic interval queries" in {
    val config = createSourceConfiguration(1)
    config.addProperty(exclusionKey(0, "days.day"),
      Array("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"))
    config.addProperty(exclusionKey(0, "hours[@from]"), "21")
    config.addProperty(exclusionKey(0, "hours[@to]"), "23")

    val sourceConfig = RadioSourceConfig(config)
    val exclusions = sourceConfig.exclusions(radioSource(1))
    exclusions should have size 1
    assertBefore(exclusions.head, LocalDateTime.of(2016, Month.JULY, 2, 8, 0),
      LocalDateTime.of(2016, Month.JULY, 4, 21, 0))
  }

  it should "support multiple exclusion per radio source" in {
    val config = createSourceConfiguration(2)
    config.addProperty(exclusionKey(0, "days.day"),
      Array("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"))
    config.addProperty(exclusionKey(0, "hours[@from]"), "6")
    config.addProperty(exclusionKey(0, "hours[@to]"), "20")
    config.addProperty(exclusionKey(0, "minutes[@from]"), "27")
    config.addProperty(exclusionKey(0, "minutes[@to]"), "30")
    config.addProperty(exclusionKey(0, "days.day", Some(-1)), "SATURDAY")
    config.addProperty(exclusionKey(0, "hours[@from]", Some(1)), "9")
    config.addProperty(exclusionKey(0, "hours[@to]", Some(1)), "12")

    val sourceConfig = RadioSourceConfig(config)
    val exclusions = sourceConfig.exclusions(radioSource(1))
    exclusions should have size 2
    assertBefore(exclusions(1), LocalDateTime.of(2016, Month.JUNE, 29, 21, 59),
      LocalDateTime.of(2016, Month.JUNE, 30, 6, 27))
    assertBefore(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 29, 22, 0),
      LocalDateTime.of(2016, Month.JULY, 2, 9, 0))
  }

  it should "return correct rankings for sources" in {
    val sources = List(sourceWithRanking(1, 5), sourceWithRanking(2, 42),
      sourceWithRanking(3, -1))
    val config = createSourceConfiguration(sources)
    val sourceConfig = RadioSourceConfig(config)

    sourceConfig ranking radioSource(1) should be(5)
    sourceConfig ranking radioSource(2) should be(42)
    sourceConfig ranking radioSource(3) should be(RadioSourceConfig.DefaultRanking)
  }

  it should "return the default ranking for an unknown source" in {
    val config = createSourceConfiguration(4)
    val sourceConfig = RadioSourceConfig(config)

    sourceConfig ranking radioSource(28) should be(RadioSourceConfig.DefaultRanking)
  }
}
