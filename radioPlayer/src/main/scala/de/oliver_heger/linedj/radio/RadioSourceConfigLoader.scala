/*
 * Copyright 2015-2023 The Developers Team.
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

import java.time.DayOfWeek
import de.oliver_heger.linedj.player.engine.interval.IntervalQueries
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.IntervalQuery
import de.oliver_heger.linedj.player.engine.radio.{RadioSource, RadioSourceConfig}
import org.apache.commons.configuration.{Configuration, ConversionException, HierarchicalConfiguration}
import org.apache.logging.log4j.LogManager

import scala.jdk.CollectionConverters._

/**
  * A module allowing to construct [[RadioSourceConfig]] instances from the
  * configuration of the radio player application.
  *
  * The configuration format covers all the data required for radio sources.
  * This is of course the declaration of the sources themselves with their
  * stream URIs and rankings. In addition, exclusions can be defined for all
  * sources. This can happen together with the source declaration.
  * Alternatively, single exclusion queries or whole sets of exclusion queries
  * can be defined globally and assigned a name. These names can then be
  * referenced from source declarations. That way queries can be shared between
  * multiple sources.
  *
  * Below is an example configuration for radio sources showing all supported
  * elements:
  *
  * {{{
  * <exclusions>
  *   <exclusion name="fullHourAds">
  *     <days>
  *       <day>MONDAY</day>
  *       <day>TUESDAY</day>
  *       <day>WEDNESDAY</day>
  *       <day>THURSDAY</day>
  *       <day>FRIDAY</day>
  *       <day>SATURDAY</day>
  *     </days>
  *     <hours from="6" to="20"/>
  *     <minutes from="57" to="60"/>
  *   </exclusion>
  * </exclusions>
  *
  * <exclusion-sets>
  *   <exclusion-set name="adsOnWorkDays">
  *     <exclusions>
  *       <exclusion>
  *         <days>
  *           <day>MONDAY</day>
  *           <day>TUESDAY</day>
  *           <day>WEDNESDAY</day>
  *           <day>THURSDAY</day>
  *           <day>FRIDAY</day>
  *         </days>
  *         <hours from="6" to="20"/>
  *         <minutes from="25" to="30"/>
  *       </exclusion>
  *       <exclusion-ref name="fullHourAds"/>
  *     </exclusions>
  *   </exclusion-set>
  * </exclusion-sets>
  *
  * <sources>
  *   <source>
  *     <name>HR 1</name>
  *     <uri>http://metafiles.gl-systemhaus.de/hr/hr1_2.m3u</uri>
  *     <ranking>42</ranking>
  *     <extension>mp3</extension>
  *     <exclusions>
  *       <exclusion>
  *         <days>
  *           <day>MONDAY</day>
  *           <day>TUESDAY</day>
  *           <day>WEDNESDAY</day>
  *           <day>THURSDAY</day>
  *           <day>FRIDAY</day>
  *         </days>
  *         <hours from="0" to="6"/>
  *         <minutes from="15" to="17"/>
  *       </exclusion>
  *       <exclusion-set-ref name="adsOnWorkDays"/>
  *     </exclusions>
  *   </source>
  * </sources>
  * }}}
  */
object RadioSourceConfigLoader {
  /**
    * Constant for a default value ranking. This ranking is assigned to a
    * radio source if no explicit value is specified in the configuration.
    */
  final val DefaultRanking = 0

  /** The key for the top-level radio section in the configuration. */
  private val KeyRadio = "radio"

  /** Configuration key for the radio sources. */
  private val KeySources = KeyRadio + ".sources.source"

  /** Configuration key for the name of a radio source. */
  private val KeySourceName = "name"

  /** Configuration key for the URI of a radio source. */
  private val KeySourceURI = "uri"

  /** Configuration key for the ranking of a radio source. */
  private val KeySourceRanking = "ranking"

  /** Configuration key for the extension of a radio source. */
  private val KeySourceExtension = "extension"

  /** The name of the section defining exclusions. */
  private val KeyExclusionsSection = "exclusions."

  /** Configuration key for the exclusions of a radio source. */
  private val KeyExclusions = KeyExclusionsSection + "exclusion"

  /** Configuration key for the exclusion set declarations. */
  private val KeyExclusionSets = KeyRadio + ".exclusion-sets.exclusion-set"

  /** Configuration key for the attribute defining an exclusion name. */
  private val KeyAttrName = "[@name]"

  /** The key for the exclusion references of a source. */
  private val KeyExclusionRef = KeyExclusionsSection + "exclusion-ref" + KeyAttrName

  /** The key for the exclusion set references of a source. */
  private val KeyExclusionSetRef = KeyExclusionsSection + "exclusion-set-ref" + KeyAttrName

  /** The key for the from attribute for interval queries. */
  private val KeyAttrFrom = "[@from]"

  /** The key for the to attribute for interval queries. */
  private val KeyAttrTo = "[@to]"

  /** The key for a minutes interval. */
  private val KeyIntervalMinutes = "minutes"

  /** The key for an hours interval. */
  private val KeyIntervalHours = "hours"

  /** The key for a day-of-week interval. */
  private val KeyIntervalDays = "days.day"

  /** The logger. */
  private val log = LogManager.getLogger(getClass)

  /**
    * Creates a new instance of ''RadioSourceConfig'' with the content of the
    * specified ''Configuration'' object.
    *
    * @param config the ''Configuration''
    * @return the new ''RadioSourceConfig''
    */
  def load(config: HierarchicalConfiguration): RadioSourceConfig = {
    val srcData = readSourcesFromConfig(config)
    val sources = srcData map (t => (t._1, t._2))
    val exclusions = srcData map (t => (t._2, t._4))
    val ranking = srcData.map(t => (t._2, t._3)).toMap
    RadioSourceConfigImpl(sources, exclusions.toMap, ranking)
  }

  /**
    * Reads the defined radio sources from the configuration.
    *
    * @param config the configuration to be processed
    * @return a sequence with the extracted radio sources: the name, the source
    *         itself, the ranking, and the associated interval queries
    */
  private def readSourcesFromConfig(config: HierarchicalConfiguration):
  Seq[(String, RadioSource, Int, Seq[IntervalQuery])] = {
    val namedExclusions = readNamedExclusions(config)
    val exclusionSets = readExclusionSets(config, namedExclusions)

    val srcConfigs = config.configurationsAt(KeySources)
    import scala.jdk.CollectionConverters._
    val sources = srcConfigs.asScala filter { c =>
      c.containsKey(KeySourceName) && c.containsKey(KeySourceURI)
    } map { c =>
      (c.getString(KeySourceName), RadioSource(c.getString(KeySourceURI), Option(c.getString(KeySourceExtension))),
        c.getInt(KeySourceRanking, DefaultRanking),
        readExclusionsSection(c, namedExclusions, exclusionSets))
    }

    sources.sortWith(compareSources).toSeq
  }

  /**
    * Parses the section with named exclusions.
    *
    * @param config the configuration
    * @return a map with named exclusion queries
    */
  private def readNamedExclusions(config: HierarchicalConfiguration): Map[String, IntervalQuery] =
    if (config.getMaxIndex(KeyRadio) < 0) Map.empty
    else readExclusions(config.configurationAt(KeyRadio))
      .filter(_._2.isDefined)
      .map(t => (t._2.get, t._1))
      .toMap

  /**
    * Reads the global section with exclusion sets. The queries in these sets
    * can then be referenced from source declarations.
    *
    * @param config          the radio configuration
    * @param namedExclusions the map with named exclusion queries
    * @return a map with exclusion sets and their queries
    */
  private def readExclusionSets(config: HierarchicalConfiguration, namedExclusions: Map[String, IntervalQuery]):
  Map[String, Seq[IntervalQuery]] = {
    val setConfigs = config configurationsAt KeyExclusionSets
    setConfigs.asScala.filter(_.containsKey(KeyAttrName)).map { c =>
      val name = c.getString(KeyAttrName)
      val exclusions = readExclusionsSection(c, namedExclusions, Map.empty)
      (name, exclusions)
    }.toMap
  }

  /**
    * Parses the exclusions definition from the given configuration. The
    * configuration can either point to a specific radio source or to the
    * global section with named exclusions.
    *
    * @param config the sub configuration for the current source
    * @return a sequence with all extracted interval queries and their optional
    *         names
    */
  private def readExclusions(config: HierarchicalConfiguration): Seq[(IntervalQuery, Option[String])] = {
    val exConfigs = config configurationsAt KeyExclusions
    exConfigs.asScala.foldLeft(List.empty[(IntervalQuery, Option[String])]) { (q, c) =>
      parseIntervalQuery(c) match {
        case Some(query) =>
          val exclusionName = Option(c.getString(KeyAttrName))
          (IntervalQueries.cyclic(query), exclusionName) :: q
        case None => q
      }
    }
  }

  /**
    * Reads a section with exclusions which can be either for a source or for
    * an exclusion set.
    *
    * @param config          the configuration containing the section
    * @param namedExclusions the map with named exclusions
    * @param exclusionSets   the map with exclusion sets
    * @return the sequence with exclusions for this source
    */
  private def readExclusionsSection(config: HierarchicalConfiguration,
                                    namedExclusions: Map[String, IntervalQuery],
                                    exclusionSets: Map[String, Seq[IntervalQuery]]): Seq[IntervalQuery] = {
    val inlineExclusions = readExclusions(config).map(_._1)
    val referencedExclusions = readReferences(config, KeyExclusionRef, namedExclusions)
    val referencedExclusionSets = readReferences(config, KeyExclusionSetRef, exclusionSets).flatten

    inlineExclusions ++ referencedExclusions ++ referencedExclusionSets
  }

  /**
    * Reads reference elements from the given configuration and resolves them
    * using the provided map. This is used to process references to named
    * queries or exclusion sets.
    *
    * @param config the configuration
    * @param key    the key of the reference elements
    * @param refMap the map to resolve the references
    * @tparam T the value type of the map
    * @return a sequence with the resolved elements
    */
  private def readReferences[T](config: HierarchicalConfiguration, key: String, refMap: Map[String, T]): Seq[T] =
    config.getList(key).asScala
      .map(_.toString)
      .map(refMap.get)
      .filter(_.isDefined)
      .map(_.get).toSeq

  /**
    * Compares two elements from the sequence of radio source data. This is
    * used to order the radio sources based on their ranking and their name.
    *
    * @param t1 the first tuple
    * @param t2 the second tuple
    * @return '''true''' if t1 is less than t2
    */
  private def compareSources(t1: (String, RadioSource, Int, Seq[_]),
                             t2: (String, RadioSource, Int, Seq[_])): Boolean =
    if (t1._3 != t2._3) t1._3 > t2._3
    else t1._1 < t2._1

  /**
    * Tries to parse an interval query from an exclusion element in the
    * configuration.
    *
    * @param c the sub configuration for a single source
    * @return an option for the extracted interval query
    */
  private def parseIntervalQuery(c: Configuration): Option[IntervalQuery] = {
    val minutes = parseRangeQuery(c, 60, KeyIntervalMinutes)(IntervalQueries.minutes)
    val hours = parseRangeQuery(c, 24, KeyIntervalHours)(IntervalQueries.hours)
    val days = parseWeekDayQuery(c)

    val parts = days :: hours :: minutes :: Nil
    parts.flatten.reduceOption(IntervalQueries.combine)
  }

  /**
    * Parses an exclusion definition for a range-based query.
    *
    * @param c   the configuration
    * @param max the maximum value for a parameter value
    * @param key the configuration key prefix for query parameters
    * @param f   the function for creating the query
    * @return an option for the parsed query
    */
  private def parseRangeQuery(c: Configuration, max: Int, key: String)(f: (Int, Int) =>
    IntervalQuery): Option[IntervalQuery] = {
    def checkValue(value: Int): Boolean =
      value >= 0 && value <= max

    if (c.containsKey(key + KeyAttrFrom) && c.containsKey(key + KeyAttrTo)) {
      try {
        val from = c.getInt(key + KeyAttrFrom)
        val to = c.getInt(key + KeyAttrTo)
        if (checkValue(from) && checkValue(to) && from < to)
          Some(f(from, to))
        else {
          log.warn(s"Invalid query parameters ($from, $to) for key $key.")
          None
        }
      } catch {
        case e: ConversionException =>
          log.warn("Non parsable values in exclusion definition!", e)
          None
      }
    } else None
  }

  /**
    * Parses an exclusion definition based on days of week.
    *
    * @param c the configuration
    * @return an option for the parsed query
    */
  private def parseWeekDayQuery(c: Configuration): Option[IntervalQuery] = {
    val days = c.getStringArray(KeyIntervalDays)
    try {
      val daySet = days.map(DayOfWeek.valueOf(_).getValue).toSet
      if (daySet.nonEmpty) Some(IntervalQueries.weekDaySet(daySet))
      else None
    } catch {
      case i: IllegalArgumentException =>
        log.warn("Could not parse day-of-week query!", i)
        None
    }
  }

  /**
    * Internal implementation of the radio source config trait as a plain case
    * class.
    *
    * @param namedSources  the list with sources
    * @param exclusionsMap the map with exclusions
    * @param rankingMap    a map with source rankings
    */
  private case class RadioSourceConfigImpl(override val namedSources: Seq[(String, RadioSource)],
                                           exclusionsMap: Map[RadioSource, Seq[IntervalQuery]],
                                           rankingMap: Map[RadioSource, Int])
    extends RadioSourceConfig {
    override def ranking(source: RadioSource): Int =
      rankingMap.getOrElse(source, DefaultRanking)

    override def exclusions(source: RadioSource): Seq[IntervalQuery] = exclusionsMap.getOrElse(source, Seq.empty)
  }
}
