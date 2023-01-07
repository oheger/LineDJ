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
import de.oliver_heger.linedj.player.engine.radio.RadioSource
import org.apache.commons.configuration.{Configuration, ConversionException, HierarchicalConfiguration}
import org.apache.logging.log4j.LogManager

import scala.jdk.CollectionConverters._

/**
  * Companion object for ''RadioSourceConfig''.
  *
  * This object allows the creation of ''RadioSourceConfig'' instances.
  */
object RadioSourceConfig {
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

  /** Configuration key for the exclusions of a radio source. */
  private val KeyExclusions = "exclusions.exclusion"

  /** Configuration key for the attribute defining an exclusion name. */
  private val KeyAttrName = "[@name]"

  /** The key for the exclusion references of a source. */
  private val KeyExclusionRef = "exclusions.exclusion-ref" + KeyAttrName

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
  private val log = LogManager.getLogger(classOf[RadioSourceConfig])

  /**
    * Creates a new instance of ''RadioSourceConfig'' with the content of the
    * specified ''Configuration'' object.
    *
    * @param config the ''Configuration''
    * @return the new ''RadioSourceConfig''
    */
  def apply(config: HierarchicalConfiguration): RadioSourceConfig = {
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
    val namedExclusions = if (config.getMaxIndex(KeyRadio) < 0) Map.empty[String, IntervalQuery]
    else
      readExclusions(config.configurationAt(KeyRadio))
        .filter(_._2.isDefined)
        .map(t => (t._2.get, t._1))
        .toMap

    val srcConfigs = config.configurationsAt(KeySources)
    import scala.jdk.CollectionConverters._
    val sources = srcConfigs.asScala filter { c =>
      c.containsKey(KeySourceName) && c.containsKey(KeySourceURI)
    } map { c =>
      (c.getString(KeySourceName), RadioSource(c.getString(KeySourceURI), Option(c.getString(KeySourceExtension))),
        c.getInt(KeySourceRanking, DefaultRanking),
        readExclusionsForSource(c, namedExclusions))
    }

    sources.sortWith(compareSources).toSeq
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
    * Reads the exclusions for the current source as defined by the provided
    * configuration.
    *
    * @param config          the configuration (for the current source)
    * @param namedExclusions the map with named exclusions
    * @return the sequence with exclusions for this source
    */
  private def readExclusionsForSource(config: HierarchicalConfiguration,
                                      namedExclusions: Map[String, IntervalQuery]): Seq[IntervalQuery] = {
    val inlineExclusions = readExclusions(config).map(_._1)

    val referencedExclusions = config.getList(KeyExclusionRef).asScala
      .map(_.toString)
      .map(namedExclusions.get)
      .filter(_.isDefined)
      .map(_.get)

    inlineExclusions ++ referencedExclusions
  }

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
    * @param sources       the list with sources
    * @param exclusionsMap the map with exclusions
    * @param rankingMap    a map with source rankings
    */
  private case class RadioSourceConfigImpl(override val sources: Seq[(String, RadioSource)],
                                           exclusionsMap: Map[RadioSource, Seq[IntervalQuery]],
                                           rankingMap: Map[RadioSource, Int])
    extends RadioSourceConfig {
    override def ranking(source: RadioSource): Int =
      rankingMap.getOrElse(source, DefaultRanking)

    override def exclusions(source: RadioSource): Seq[IntervalQuery] = exclusionsMap.getOrElse(source, Seq.empty)
  }
}

/**
  * A trait allowing access to configuration information about radio sources.
  *
  * Instances of this class are created from the current configuration of the
  * radio player application. They contain all information available about
  * radio sources, i.e. the source declarations (including name and URI) plus
  * optional exclusions for each source.
  *
  * An exclusion is an interval in which a radio source should not be played;
  * for instance, a station might send commercials for each half or full hour.
  * Then [[IntervalQuery]] instances can be defined excluding these times. Such
  * exclusions can be declared inline together with source declarations or in a
  * separate section with a name; they can then be shared by multiple sources.
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
  *         <hours from="6" to="20"/>
  *         <minutes from="25" to="30"/>
  *       </exclusion>
  *       <exclusion-ref name="fullHourAdds"/>
  *     </exclusions>
  *   </source>
  * </sources>
  * }}}
  */
trait RadioSourceConfig {
  /**
    * Returns a list with all available radio sources. A source is described
    * by a tuple: The first element is a human-readable name of the source,
    * the second element is the ''RadioSource'' object itself.
    *
    * @return a sequence with all available radio sources
    */
  def sources: Seq[(String, RadioSource)]

  /**
    * Returns exclusions for specific radio sources. This function returns a
    * collection of interval queries defining time intervals in which the given
    * source should not be played. An empty sequence means that there are no
    * exclusions defined for this source.
    *
    * @param source the source in question
    * @return a sequence with exclusions for a this radio source
    */
  def exclusions(source: RadioSource): Seq[IntervalQuery]

  /**
    * Returns a ranking for the specified radio source. The ranking is an
    * arbitrary numeric value which is read from the configuration.
    *
    * @param source the source to be ranked
    * @return the ranking for this source
    */
  def ranking(source: RadioSource): Int
}
