/*
 * Copyright 2015-2022 The Developers Team.
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
import de.oliver_heger.linedj.player.engine.RadioSource
import de.oliver_heger.linedj.player.engine.interval.IntervalQueries
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.IntervalQuery
import org.apache.commons.configuration.{Configuration, ConversionException, HierarchicalConfiguration}
import org.apache.logging.log4j.LogManager

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
  val DefaultRanking = 0

  /** Configuration key for the radio sources. */
  private val KeySources = "radio.sources.source"

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
  def apply(config: Configuration): RadioSourceConfig = {
    val srcData = readSourcesFromConfig(config)
    val sources = srcData map (t => (t._1, t._2))
    val exclusions = srcData map(t => (t._2, t._4))
    val ranking = srcData.map(t => (t._2, t._3)).toMap
    RadioSourceConfigImpl(sources, exclusions.toMap, ranking)
  }

  /**
    * Reads the defined radio sources from the configuration.
    *
    * @return a sequence with the extracted radio sources: the name, the source
    *         itself, the ranking, and the associated interval queries
    */
  private def readSourcesFromConfig(config: Configuration): Seq[(String, RadioSource, Int,
    Seq[IntervalQuery])] = {
    val srcConfigs = config.asInstanceOf[HierarchicalConfiguration].configurationsAt(KeySources)
    import scala.jdk.CollectionConverters._
    val sources = srcConfigs.asScala filter { c =>
      c.containsKey(KeySourceName) && c.containsKey(KeySourceURI)
    } map { c =>
      (c.getString(KeySourceName), RadioSource(c.getString(KeySourceURI), Option(c.getString
      (KeySourceExtension))), c.getInt(KeySourceRanking, DefaultRanking), readExclusions(c))
    }
    sources.sortWith(compareSources).toSeq
  }

  /**
    * Parses the exclusions definition for a radio source declaration.
    *
    * @param config the sub configuration for the current source
    * @return a sequence with all extracted interval queries
    */
  private def readExclusions(config: HierarchicalConfiguration): Seq[IntervalQuery] = {
    val exConfigs = config configurationsAt KeyExclusions
    import scala.jdk.CollectionConverters._
    exConfigs.asScala.foldLeft(List.empty[IntervalQuery]) { (q, c) =>
      parseIntervalQuery(c) match {
        case Some(query) => IntervalQueries.cyclic(query) :: q
        case None => q
      }
    }
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
    * @param sources    the list with sources
    * @param exclusions the map with exclusions
    * @param rankingMap a map with source rankings
    */
  private case class RadioSourceConfigImpl(override val sources: Seq[(String, RadioSource)],
                                           override val exclusions: Map[RadioSource,
                                             Seq[IntervalQuery]],
                                           rankingMap: Map[RadioSource, Int])
    extends RadioSourceConfig {
    override def ranking(source: RadioSource): Int =
      rankingMap.getOrElse(source, DefaultRanking)
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
  * Then an ''interval query'' can be defined excluding these times.
  *
  * Below is an example configuration for radio sources showing all supported
  * elements:
  *
  * {{{
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
    * Returns a map with information about exclusions for radio sources. This
    * map defines time intervals in which specific sources should not be
    * played. If a radio source is not contained in the map, this means that
    * there are no exclusions defined for it.
    *
    * @return a map with exclusions for radio sources
    */
  def exclusions: Map[RadioSource, Seq[IntervalQuery]]

  /**
    * Returns a ranking for the specified radio source. The ranking is an
    * arbitrary numeric value which is read from the configuration.
    *
    * @param source the source to be ranked
    * @return the ranking for this source
    */
  def ranking(source: RadioSource): Int
}
