/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.radio.client.config

import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{Before, Inside, IntervalQuery}
import de.oliver_heger.linedj.player.engine.radio.RadioSource
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.MatchContext.MatchContext
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.ResumeMode.ResumeMode
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.{MatchContext, MetadataExclusion, ResumeMode}
import org.apache.commons.configuration.{HierarchicalConfiguration, XMLConfiguration}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{LocalDateTime, Month}
import scala.collection.immutable.Seq
import scala.concurrent.duration.*

object RadioSourceConfigLoaderSpec:
  /** Prefix for a radio source name. */
  private val RadioSourceName = "Radio_"

  /** Prefix for a radio source URI. */
  private val RadioSourceURI = "http://rad.io/"

  /** The test configuration file loaded from disk. */
  private val TestConfig = new XMLConfiguration("test-radio-configuration.xml")

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
    RadioSource(RadioSourceURI + idx, if hasDefaultExt(idx) then Some("mp3") else None)

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
    * @param count   the number of radio sources
    * @param rootKey the root key of the configuration
    * @return the configuration defining the number of radio sources
    */
  private def createSourceConfiguration(count: Int, rootKey: String = "radio"): HierarchicalConfiguration =
    val sources = (1 to count) map (i => (sourceName(i), radioSource(i), -1))
    createSourceConfiguration(sources, rootKey)

  /**
    * Creates a configuration that contains the specified radio sources. The
    * passed in list contains tuples defining a radio source each: its name,
    * its URI, and its ranking. Ranking values less than zero are ignored and
    * not written into the configuration. It is possible to specify the root
    * key under which the data is written.
    *
    * @param sources a list with the sources to be contained (with ranking)
    * @param rootKey the root key of the configuration
    * @return the configuration with these radio sources
    */
  private def createSourceConfiguration(sources: Seq[(String, RadioSource, Int)],
                                        rootKey: String): HierarchicalConfiguration =
    val config = new HierarchicalConfiguration
    sources foreach { t =>
      config.addProperty(s"$rootKey.sources.source(-1).name", t._1)
      config.addProperty(s"$rootKey.sources.source.uri", t._2.uri)
      if t._3 > 0 then
        config.addProperty(s"$rootKey.sources.source.ranking", t._3)
      t._2.defaultExtension foreach (config.addProperty(s"$rootKey.sources.source.extension", _))
    }
    config

  /**
    * Returns an initialized builder for a source configuration that already
    * contains the given number of sources.
    *
    * @param srcCount the number of sources to include
    * @param rootKey  the root key under which to store configuration data
    * @return the builder for the configuration
    */
  private def buildSourceConfiguration(srcCount: Int, rootKey: String = "radio"): SourceConfigBuilder =
    new SourceConfigBuilder(createSourceConfiguration(srcCount, rootKey), rootKey)

  /**
    * A helper class for constructing a configuration instance from which a
    * [[RadioSourceConfigLoader]] can be read.
    *
    * @param config  the underlying hierarchical configuration
    * @param rootKey the root key for the configuration
    */
  private class SourceConfigBuilder(val config: HierarchicalConfiguration,
                                    rootKey: String):
    /**
      * Stores the key of the current context of the builder. The context is
      * changed when a new element is created that has exclusions as content.
      * The builder methods then produce keys relative to this context.
      */
    private var contextKey: String = rootKey

    /**
      * Changes this builder's context to point to a new exclusion for the
      * source with the given index.
      *
      * @param srcIdx the index of the source
      * @return a reference to this builder
      */
    def withSourceExclusion(srcIdx: Int): SourceConfigBuilder =
      contextKey = s"$rootKey.sources.source($srcIdx).exclusions.exclusion(-1)"
      this

    /**
      * Adds a property to reference an exclusion by name.
      *
      * @param name the name of the referenced exclusion
      * @return a reference to this builder
      */
    def withExclusionReference(name: String): SourceConfigBuilder =
      addReferenceElement("exclusion-ref", name)

    /**
      * Adds an exclusion to the managed configuration. If a name is provided,
      * a top-level exclusion is created; otherwise, the exclusion is created
      * in the current context.
      *
      * @param name the name of the exclusion
      * @return a reference to this builder
      */
    def withExclusion(name: Option[String]): SourceConfigBuilder =
      contextKey = name match
        case Some(value) =>
          config.addProperty(s"$rootKey.exclusions.exclusion(-1)[@name]", value)
          s"$rootKey.exclusions.exclusion"
        case None =>
          contextKey.stripSuffix(".exclusions.exclusion") + ".exclusions.exclusion(-1)"
      this

    /**
      * Adds an exclusion set element with the given name.
      *
      * @param name the name of the exclusion set
      * @return a reference to this builder
      */
    def withExclusionSet(name: String): SourceConfigBuilder =
      contextKey = rootKey
      addProperty("exclusion-sets.exclusion-set(-1)[@name]", name, s"$rootKey.exclusion-sets.exclusion-set")

    /**
      * Adds a property to reference an exclusion set by name.
      *
      * @param name the name of the exclusion set
      * @return a reference to this builder
      */
    def withExclusionSetReference(name: String): SourceConfigBuilder =
      addReferenceElement("exclusion-set-ref", name)

    /**
      * Adds a property to an exclusion.
      *
      * @param suffix the suffix of the exclusion key
      * @param value  the value of the property
      * @return a reference to this builder
      */
    def withExclusionProperty(suffix: String, value: Any): SourceConfigBuilder =
      addProperty(suffix, value, removeNewElementIndexFromContext())

    /**
      * Adds a property with multiple values to an exclusion.
      *
      * @param suffix the suffix of the exclusion key
      * @param values the array with the values to be added
      * @return a reference to this builder
      */
    def withExclusionProperty(suffix: String, values: Array[String]): SourceConfigBuilder =
      withExclusionProperty(suffix, values.head)
      withExclusionProperty(suffix, values.tail.asInstanceOf[Any])

    /**
      * Adds a reference to a named element to the current exclusions section.
      *
      * @param key  the key of the reference element
      * @param name the name of the element that is referenced
      * @return a reference to this builder
      */
    private def addReferenceElement(key: String, name: String): SourceConfigBuilder =
      contextKey = removeNewElementIndexFromContext().stripSuffix(".exclusion")
      addProperty(s"$key[@name]", name, removeNewElementIndexFromContext())

    /**
      * Helper function to add a property to the managed configuration.
      *
      * @param key        the property key
      * @param value      the property value
      * @param newContext the new key to set for the context
      * @return a reference to this builder
      */
    private def addProperty(key: String, value: Any, newContext: String = contextKey): SourceConfigBuilder =
      val fullKey = s"$contextKey.$key"
      config.addProperty(fullKey, value)
      contextKey = newContext
      this

    /**
      * Returns the context key with a suffix removed that selects a new
      * configuration element. This means that further add property operations
      * modify the latest element.
      *
      * @return the modified context key
      */
    private def removeNewElementIndexFromContext(): String = contextKey.stripSuffix("(-1)")

/**
  * Test class for ''RadioSourceConfig''.
  */
class RadioSourceConfigLoaderSpec extends AnyFlatSpec with Matchers:

  import RadioSourceConfigLoaderSpec.*

  /**
    * Checks whether the specified interval query yields a Before result for
    * the given date with the provided start time.
    *
    * @param query the query
    * @param date  the reference date
    * @param start the start time
    */
  private def assertBefore(query: IntervalQuery, date: LocalDateTime, start: LocalDateTime): Unit =
    query(date) match
      case Before(d) => d.value should be(start)
      case r => fail("Unexpected result: " + r)

  /**
    * Checks whether the specified interval query yields an Inside result for
    * the given date with the provided until time.
    *
    * @param query the query
    * @param date  the reference date
    * @param until the until time
    */
  private def assertInside(query: IntervalQuery, date: LocalDateTime, until: LocalDateTime): Unit =
    query(date) match
      case Inside(d, _) => d.value should be(until)
      case r => fail("Unexpected result: " + r)

  /**
    * Checks the single properties of a metadata exclusion.
    *
    * @param exclusion      the exclusion to check
    * @param matchContext   the expected match context
    * @param resumeMode     the expected resume mode
    * @param interval       the expected check interval
    * @param optName        the expected optional name
    * @param matchString    a string to be matched by the pattern
    * @param nonMatchString a string not to be matched by the pattern
    * @return the checked exclusion
    */
  private def checkMetadataExclusion(exclusion: MetadataExclusion,
                                     matchContext: MatchContext,
                                     resumeMode: ResumeMode,
                                     interval: FiniteDuration,
                                     optName: Option[String],
                                     matchString: String,
                                     nonMatchString: String): MetadataExclusion =
    exclusion.matchContext should be(matchContext)
    exclusion.resumeMode should be(resumeMode)
    exclusion.checkInterval should be(interval)
    exclusion.name should be(optName)
    val matcher1 = exclusion.pattern.matcher(matchString)
    matcher1.matches() shouldBe true
    val matcher2 = exclusion.pattern.matcher(nonMatchString)
    matcher2.matches() shouldBe false

    exclusion

  "A RadioSourceConfigLoader" should "provide a correct list of sources" in:
    val Count = 4
    val config = createSourceConfiguration(Count)

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    val expSrcList = (1 to Count) map (i => (sourceName(i), radioSource(i)))
    sourceConfig.namedSources should be(expSrcList)

  it should "read sources from an alternative key" in:
    val Count = 4
    val OtherKey = "radioSources"
    val config = createSourceConfiguration(Count, OtherKey)

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config, OtherKey)
    val expSrcList = (1 to Count) map (i => (sourceName(i), radioSource(i)))
    sourceConfig.namedSources should be(expSrcList)

  it should "ignore incomplete source definitions" in:
    val Count = 2
    val config = createSourceConfiguration(Count)
    config.addProperty("radio.sources.source(-1).name", "someName")
    config.addProperty("radio.sources.source(-1).uri", "someURI")

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    sourceConfig.namedSources should have size 2
    sourceConfig.namedSources.head._1 should be(sourceName(1))
    sourceConfig.namedSources(1)._1 should be(sourceName(2))

  it should "order radio sources by name" in:
    val sources = List((sourceName(8), radioSource(8)), (sourceName(2), radioSource(2)),
      (sourceName(3), radioSource(3)), (sourceName(9), radioSource(9)))
    val sourcesSorted = List((sourceName(2), radioSource(2)), (sourceName(3), radioSource(3)),
      (sourceName(8), radioSource(8)), (sourceName(9), radioSource(9)))
    val config = createSourceConfiguration(sources map (t => (t._1, t._2, -1)), "radio")

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    sourceConfig.namedSources should be(sourcesSorted)

  it should "order radio sources by ranking" in:
    val sources = List(sourceWithRanking(1, 1), sourceWithRanking(2, 17),
      sourceWithRanking(3, -1), sourceWithRanking(4, 8),
      sourceWithRanking(5, 1))
    val sourcesSorted = List((sourceName(2), radioSource(2)),
      (sourceName(4), radioSource(4)),
      (sourceName(1), radioSource(1)),
      (sourceName(5), radioSource(5)),
      (sourceName(3), radioSource(3)))
    val config = createSourceConfiguration(sources, "radio")

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    sourceConfig.namedSources should be(sourcesSorted)

  it should "return a map with all sources and empty lists if no exclusions are defined" in:
    val config = createSourceConfiguration(2)

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    sourceConfig.namedSources foreach { src =>
      sourceConfig.exclusions(src._2) shouldBe empty
    }

  it should "process a minutes exclusion" in:
    val config = buildSourceConfiguration(4)
      .withSourceExclusion(2)
      .withExclusionProperty("minutes[@from]", "22")
      .withExclusionProperty("minutes[@to]", 25)
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    val exclusions = sourceConfig.exclusions(radioSource(3))
    exclusions should have size 1
    assertBefore(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 29, 19, 21),
      LocalDateTime.of(2016, Month.JUNE, 29, 19, 22))
    assertInside(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 30, 22, 23),
      LocalDateTime.of(2016, Month.JUNE, 30, 22, 25))

  it should "drop an undefined exclusion" in:
    val config = buildSourceConfiguration(1)
      .withSourceExclusion(0)
      .withExclusionProperty("unsupported", "1")
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    sourceConfig.exclusions(radioSource(1)) shouldBe empty

  it should "drop a minute exclusion with an invalid from" in:
    val config = buildSourceConfiguration(1)
      .withSourceExclusion(0)
      .withExclusionProperty("minutes[@from]", "61")
      .withExclusionProperty("minutes[@to]", 52)
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    sourceConfig.exclusions(radioSource(1)) shouldBe empty

  it should "drop a minute exclusion with an invalid to" in:
    val config = buildSourceConfiguration(1)
      .withSourceExclusion(0)
      .withExclusionProperty("minutes[@from]", "55")
      .withExclusionProperty("minutes[@to]", "-1")
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    sourceConfig.exclusions(radioSource(1)) shouldBe empty

  it should "ignore a minute exclusion with from >= to" in:
    val config = buildSourceConfiguration(1)
      .withSourceExclusion(0)
      .withExclusionProperty("minutes[@from]", "58")
      .withExclusionProperty("minutes[@to]", "58")
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    sourceConfig.exclusions(radioSource(1)) shouldBe empty

  it should "ignore a minute exclusion with a non-string parameter" in:
    val config = buildSourceConfiguration(1)
      .withSourceExclusion(0)
      .withExclusionProperty("minutes[@from]", "noNumber")
      .withExclusionProperty("minutes[@to]", "58")
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    sourceConfig.exclusions(radioSource(1)) shouldBe empty

  it should "combine multiple interval queries" in:
    val config = buildSourceConfiguration(1)
      .withSourceExclusion(0)
      .withExclusionProperty("minutes[@from]", "14")
      .withExclusionProperty("minutes[@to]", "20")
      .withExclusionProperty("hours[@from]", "21")
      .withExclusionProperty("hours[@to]", "23")
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    val exclusions = sourceConfig.exclusions(radioSource(1))
    exclusions should have size 1
    assertBefore(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 29, 20, 10),
      LocalDateTime.of(2016, Month.JUNE, 29, 21, 14))
    assertInside(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 30, 22, 14),
      LocalDateTime.of(2016, Month.JUNE, 30, 22, 20))

  it should "ignore an hours exclusion with invalid values" in:
    val config = buildSourceConfiguration(1)
      .withSourceExclusion(0)
      .withExclusionProperty("hours[@from]", "21")
      .withExclusionProperty("hours[@to]", "25")
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    sourceConfig.exclusions(radioSource(1)) shouldBe empty

  it should "parse a days-of-week query" in:
    val config = buildSourceConfiguration(1)
      .withSourceExclusion(0)
      .withExclusionProperty("days.day", Array("MONDAY", "WEDNESDAY", "SATURDAY"))
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
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

  it should "ignore a days-of-week query with an invalid day" in:
    val config = buildSourceConfiguration(1)
      .withSourceExclusion(0)
      .withExclusionProperty("days.day", Array("TUESDAY", "FRIDAY", "unknown day"))
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    sourceConfig.exclusions(radioSource(1)) shouldBe empty

  it should "create cyclic interval queries" in:
    val config = buildSourceConfiguration(1)
      .withSourceExclusion(0)
      .withExclusionProperty("days.day", Array("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"))
      .withExclusionProperty("hours[@from]", "21")
      .withExclusionProperty("hours[@to]", "23")
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    val exclusions = sourceConfig.exclusions(radioSource(1))
    exclusions should have size 1
    assertBefore(exclusions.head, LocalDateTime.of(2016, Month.JULY, 2, 8, 0),
      LocalDateTime.of(2016, Month.JULY, 4, 21, 0))

  it should "support multiple exclusion per radio source" in:
    val config = buildSourceConfiguration(2)
      .withSourceExclusion(0)
      .withExclusionProperty("days.day", Array("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"))
      .withExclusionProperty("hours[@from]", "6")
      .withExclusionProperty("hours[@to]", "20")
      .withExclusionProperty("minutes[@from]", "27")
      .withExclusionProperty("minutes[@to]", "30")
      .withSourceExclusion(0)
      .withExclusionProperty("days.day", "SATURDAY")
      .withExclusionProperty("hours[@from]", "9")
      .withExclusionProperty("hours[@to]", "12")
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    val exclusions = sourceConfig.exclusions(radioSource(1))
    exclusions should have size 2
    assertBefore(exclusions(1), LocalDateTime.of(2016, Month.JUNE, 29, 21, 59),
      LocalDateTime.of(2016, Month.JUNE, 30, 6, 27))
    assertBefore(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 29, 22, 0),
      LocalDateTime.of(2016, Month.JULY, 2, 9, 0))

  it should "reference an exclusion by name" in:
    val ExclusionName = "half_hour"
    val config = buildSourceConfiguration(4)
      .withExclusion(Some(ExclusionName))
      .withExclusionProperty("minutes[@from]", "22")
      .withExclusionProperty("minutes[@to]", 25)
      .withSourceExclusion(2)
      .withExclusionReference(ExclusionName)
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    val exclusions = sourceConfig.exclusions(radioSource(3))
    exclusions should have size 1
    assertBefore(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 29, 19, 21),
      LocalDateTime.of(2016, Month.JUNE, 29, 19, 22))
    assertInside(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 30, 22, 23),
      LocalDateTime.of(2016, Month.JUNE, 30, 22, 25))

  it should "read exclusions from an alternative key" in:
    val OtherKey = "radioSourceConfig"
    val ExclusionName = "half_hour"
    val config = buildSourceConfiguration(4, OtherKey)
      .withExclusion(Some(ExclusionName))
      .withExclusionProperty("minutes[@from]", "22")
      .withExclusionProperty("minutes[@to]", 25)
      .withSourceExclusion(2)
      .withExclusionReference(ExclusionName)
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config, OtherKey)
    val exclusions = sourceConfig.exclusions(radioSource(3))
    exclusions should have size 1
    assertBefore(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 29, 19, 21),
      LocalDateTime.of(2016, Month.JUNE, 29, 19, 22))
    assertInside(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 30, 22, 23),
      LocalDateTime.of(2016, Month.JUNE, 30, 22, 25))

  it should "combine inline exclusions with referenced ones" in:
    val ExclusionName = "saturday"
    val config = buildSourceConfiguration(2)
      .withSourceExclusion(0)
      .withExclusionProperty("days.day", Array("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"))
      .withExclusionProperty("hours[@from]", "6")
      .withExclusionProperty("hours[@to]", "20")
      .withExclusionProperty("minutes[@from]", "27")
      .withExclusionProperty("minutes[@to]", "30")
      .withExclusionReference(ExclusionName)
      .withExclusion(Some(ExclusionName))
      .withExclusionProperty("days.day", "SATURDAY")
      .withExclusionProperty("hours[@from]", "9")
      .withExclusionProperty("hours[@to]", "12")
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    val exclusions = sourceConfig.exclusions(radioSource(1))
    exclusions should have size 2
    assertBefore(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 29, 21, 59),
      LocalDateTime.of(2016, Month.JUNE, 30, 6, 27))
    assertBefore(exclusions(1), LocalDateTime.of(2016, Month.JUNE, 29, 22, 0),
      LocalDateTime.of(2016, Month.JULY, 2, 9, 0))

  it should "ignore a reference to an invalid exclusion" in:
    val ExclusionName = "half_hour"
    val config = buildSourceConfiguration(4)
      .withExclusion(Some("invalid"))
      .withExclusionProperty("minutes[@from]", 57)
      .withExclusionProperty("minutes[@to]", 62)
      .withExclusion(Some(ExclusionName))
      .withExclusionProperty("minutes[@from]", "22")
      .withExclusionProperty("minutes[@to]", 25)
      .withSourceExclusion(2)
      .withExclusionReference(ExclusionName)
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    val exclusions = sourceConfig.exclusions(radioSource(3))
    exclusions should have size 1
    assertBefore(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 29, 19, 21),
      LocalDateTime.of(2016, Month.JUNE, 29, 19, 22))
    assertInside(exclusions.head, LocalDateTime.of(2016, Month.JUNE, 30, 22, 23),
      LocalDateTime.of(2016, Month.JUNE, 30, 22, 25))

  it should "reference an exclusion set by name" in:
    val ExclusionSetName = "half_and_full_hour"
    val ExclusionName = "half_hour"
    val config = buildSourceConfiguration(1)
      .withExclusion(Some(ExclusionName))
      .withExclusionProperty("minutes[@from]", "22")
      .withExclusionProperty("minutes[@to]", 25)
      .withExclusionSet(ExclusionSetName)
      .withExclusion(None)
      .withExclusionProperty("minutes[@from]", "58")
      .withExclusionProperty("minutes[@to]", 60)
      .withExclusionReference(ExclusionName)
      .withSourceExclusion(0)
      .withExclusionSetReference(ExclusionSetName)
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    val exclusions = sourceConfig.exclusions(radioSource(1))
    exclusions should have size 2
    assertInside(exclusions.head, LocalDateTime.of(2023, Month.JANUARY, 7, 21, 59),
      LocalDateTime.of(2023, Month.JANUARY, 7, 22, 0))
    assertInside(exclusions(1), LocalDateTime.of(2023, Month.JANUARY, 7, 21, 22),
      LocalDateTime.of(2023, Month.JANUARY, 7, 21, 25))

  it should "combine inline exclusions with exclusion sets" in:
    val ExclusionSetName = "weekend"
    val config = buildSourceConfiguration(1)
      .withExclusionSet(ExclusionSetName)
      .withExclusion(None)
      .withExclusionProperty("days.day", Array("SATURDAY", "SUNDAY"))
      .withSourceExclusion(0)
      .withExclusionProperty("minutes[@from]", 27)
      .withExclusionProperty("minutes[@to]", 30)
      .withExclusionSetReference(ExclusionSetName)
      .config

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    val exclusions = sourceConfig.exclusions(radioSource(1))
    exclusions should have size 2
    assertBefore(exclusions(1), LocalDateTime.of(2023, Month.JANUARY, 6, 21, 51),
      LocalDateTime.of(2023, Month.JANUARY, 7, 0, 0))
    assertInside(exclusions.head, LocalDateTime.of(2023, Month.JANUARY, 7, 21, 28),
      LocalDateTime.of(2023, Month.JANUARY, 7, 21, 30))

  it should "ignore exclusion sets without names" in:
    val ExclusionSetName = "weekend"
    val config = buildSourceConfiguration(1)
      .withExclusionSet(ExclusionSetName)
      .withExclusion(None)
      .withExclusionProperty("days.day", Array("SATURDAY", "SUNDAY"))
      .withExclusionSet("toBeDeleted")
      .withExclusion(None)
      .withExclusionProperty("days.day", "MONDAY")
      .withSourceExclusion(0)
      .withExclusionSetReference(ExclusionSetName)
      .config
    config.clearProperty("radio.exclusion-sets.exclusion-set(1)[@name]")

    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)
    val exclusions = sourceConfig.exclusions(radioSource(1))
    exclusions should have size 1
    assertBefore(exclusions.head, LocalDateTime.of(2023, Month.JANUARY, 6, 21, 51),
      LocalDateTime.of(2023, Month.JANUARY, 7, 0, 0))

  it should "return correct rankings for sources" in:
    val sources = List(sourceWithRanking(1, 5), sourceWithRanking(2, 42),
      sourceWithRanking(3, -1))
    val config = createSourceConfiguration(sources, "radio")
    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)

    sourceConfig ranking radioSource(1) should be(5)
    sourceConfig ranking radioSource(2) should be(42)
    sourceConfig ranking radioSource(3) should be(RadioSourceConfigLoader.DefaultRanking)

  it should "return the default ranking for an unknown source" in:
    val config = createSourceConfiguration(4)
    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(config)

    sourceConfig ranking radioSource(28) should be(RadioSourceConfigLoader.DefaultRanking)
    
  it should "return the favorite sources in correct order" in:
    val expectedFavorites = List(
      "Classic Rock" -> RadioSource("http://www.rockantenne.de/webradio/channels/classic-perlen.m3u", Some("mp3")),
      "SWR 1 BW" -> RadioSource("http://mp3-live.swr.de/swr1bw_m.m3u", Some("mp3")),
      "HR 1" -> RadioSource("http://metafiles.gl-systemhaus.de/hr/hr1_2.m3u", Some("mp3"))
    )
    val sourceConfig = RadioSourceConfigLoader.loadSourceConfig(TestConfig)
    
    sourceConfig.favorites should contain theSameElementsInOrderAs expectedFavorites

  it should "read global metadata exclusions" in:
    val metaConfig = RadioSourceConfigLoader.loadMetadataConfig(TestConfig)

    metaConfig.exclusions should have size 3
    val exclusion1 = checkMetadataExclusion(exclusion = metaConfig.exclusions.head,
      matchContext = MatchContext.Artist,
      resumeMode = ResumeMode.NextSong,
      interval = 120.seconds,
      optName = Some("Unwanted music"),
      matchString = "James Blunt",
      nonMatchString = "AC/DC")
    exclusion1.hasTimeRestrictions shouldBe false

    checkMetadataExclusion(exclusion = metaConfig.exclusions(1),
      matchContext = MatchContext.Raw,
      resumeMode = ResumeMode.MetadataChange,
      interval = 30.seconds,
      optName = None,
      matchString = "Commercials and Spots",
      nonMatchString = "News and culture")

    val exclusion3 = checkMetadataExclusion(exclusion = metaConfig.exclusions(2),
      matchContext = MatchContext.Raw,
      resumeMode = ResumeMode.MetadataChange,
      interval = 29.seconds,
      optName = Some("timeRestricted"),
      matchString = "Annoying ads :-(",
      nonMatchString = "deep thoughts")
    assertInside(exclusion3.applicableAt.head,
      LocalDateTime.of(2023, Month.MAY, 7, 19, 57, 10),
      LocalDateTime.of(2023, Month.MAY, 7, 20, 0, 0))
    assertBefore(exclusion3.applicableAt(1),
      LocalDateTime.of(2023, Month.MAY, 8, 12, 25, 38),
      LocalDateTime.of(2023, Month.MAY, 8, 12, 27))

  /**
    * Helper function for testing a metadata config read by the loader.
    *
    * @param metaConfig the config to be checked
    */
  private def checkMetadataConfig(metaConfig: MetadataConfig): Unit =
    val source = RadioSource("http://metafiles.gl-systemhaus.de/hr/hr1_2.m3u")
    val sourceConfig = metaConfig.metadataSourceConfig(source)

    val matcher = sourceConfig.optSongPattern.get.matcher("Dire Straits/Brothers in arms")
    matcher.matches() shouldBe true
    matcher.group(MetadataConfig.ArtistGroup) should be("Dire Straits")
    matcher.group(MetadataConfig.SongTitleGroup) should be("Brothers in arms")

    sourceConfig.resumeIntervals should have size 2
    assertInside(sourceConfig.resumeIntervals(1),
      LocalDateTime.of(2023, Month.APRIL, 15, 20, 0, 10),
      LocalDateTime.of(2023, Month.APRIL, 15, 20, 3, 0))
    assertInside(sourceConfig.resumeIntervals.head,
      LocalDateTime.of(2023, Month.APRIL, 15, 21, 30, 55),
      LocalDateTime.of(2023, Month.APRIL, 15, 21, 32, 0))

    sourceConfig.exclusions should have size 2
    val exclusion1 = checkMetadataExclusion(exclusion = sourceConfig.exclusions.head,
      matchContext = MatchContext.Title,
      resumeMode = ResumeMode.MetadataChange,
      interval = 1.minute,
      optName = None,
      matchString = "Werbung im Rundfunk",
      nonMatchString = "cool music")
    exclusion1.hasTimeRestrictions shouldBe false
    val exclusion2 = checkMetadataExclusion(exclusion = sourceConfig.exclusions(1),
      matchContext = MatchContext.Title,
      resumeMode = ResumeMode.MetadataChange,
      interval = 45.seconds,
      matchString = "To skip at full hours!",
      nonMatchString = "Something else",
      optName = None)
    assertInside(exclusion2.applicableAt.head,
      LocalDateTime.of(2023, Month.MAY, 8, 21, 59, 43),
      LocalDateTime.of(2023, Month.MAY, 8, 22, 0))

  it should "read metadata information for radio sources" in:
    val metaConfig = RadioSourceConfigLoader.loadMetadataConfig(TestConfig)
    checkMetadataConfig(metaConfig)

  it should "read metadata information for radio sources from an alternative key" in:
    val config = new XMLConfiguration("test-radio-configuration-alternative-key.xml")
    val metaConfig = RadioSourceConfigLoader.loadMetadataConfig(config, "radioSources")
    checkMetadataConfig(metaConfig)

  it should "handle radio sources without metadata configuration" in:
    val source = RadioSource("http://metafiles.gl-systemhaus.de/hr/hr2_2.m3u")
    val metaConfig = RadioSourceConfigLoader.loadMetadataConfig(TestConfig)

    val sourceConfig = metaConfig.metadataSourceConfig(source)

    sourceConfig should be(MetadataConfig.EmptySourceConfig)
