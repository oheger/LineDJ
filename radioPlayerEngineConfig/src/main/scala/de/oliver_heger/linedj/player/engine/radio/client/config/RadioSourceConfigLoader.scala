/*
 * Copyright 2015-2025 The Developers Team.
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

import de.oliver_heger.linedj.player.engine.interval.IntervalQueries
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.IntervalQuery
import de.oliver_heger.linedj.player.engine.radio.RadioSource
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.{MatchContext, MetadataExclusion, RadioSourceMetadataConfig, ResumeMode}
import de.oliver_heger.linedj.player.engine.radio.config.RadioSourceConfig.DefaultRanking
import de.oliver_heger.linedj.player.engine.radio.config.{MetadataConfig, RadioSourceConfig}
import org.apache.commons.configuration.{Configuration, ConversionException, HierarchicalConfiguration}
import org.apache.logging.log4j.LogManager

import java.time.DayOfWeek
import java.util.regex.Pattern
import scala.collection.immutable.Seq
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
  * A module allowing to construct [[RadioSourceConfig]] and [[MetadataConfig]]
  * instances from the configuration of the radio player application.
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
  * Patterns can be defined to disable radio sources temporarily when they are
  * matched against the metadata of the currently played radio source. This
  * allows for instance to skip specific artists or advertisements. The
  * configuration can define a global list of such metadata exclusions. In
  * addition, each radio source can have a metadata configuration with its own
  * specific exclusions and some further properties to tweak the detection of
  * unwanted content.
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
  * <metadataExclusions>
  *   <metadataExclusion name="Unwanted music">
  *     <pattern>.*Blunt.*</pattern>
  *     <matchContext>Artist</matchContext>
  *     <resumeMode>NextSong</resumeMode>
  *     <checkInterval>120</checkInterval>
  *   </metadataExclusion>
  *   <metadataExclusion>
  *     <pattern>.*Spots.*</pattern>
  *     <checkInterval>30</checkInterval>
  *   </metadataExclusion>
  * </metadataExclusions>
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
  *     <metadata>
  *       <songPattern>(?&lt;artist>[^/]+)/\s*(?&lt;title>.+)</songPattern>
  *       <resumeIntervals>
  *         <resumeInterval>
  *           <minutes from="0" to="3" />
  *         </resumeInterval>
  *       </resumeIntervals>
  *       <metadataExclusions>
  *         <metadataExclusion>
  *           <pattern>.*Werbung.*</pattern>
  *           <matchContext>Title</matchContext>
  *           <resumeMode>MetadataChange</resumeMode>
  *           <checkInterval>60</checkInterval>
  *           <applicableAt>
  *             <time>
  *               <minutes from="28" to="31"/>
  *             </time>
  *             <time>
  *               <minutes from="56" to="60"/>
  *             </time>
  *           </applicableAt>
  *         </metadataExclusion>
  *       </metadataExclusions>
  *     </metadata>
  *   </source>
  * </sources>
  * <favorites>
  *   <favorite>
  *     <sourceRef>Rockantenne Klassik</sourceRef>
  *     <displayName>Classic Rock</displayName>
  *   </favorite>
  *   <favorite>
  *     <sourceRef>SWR 1 BW</sourceRef>
  *   </favorite>
  * </favorites>
  * }}}
  */
object RadioSourceConfigLoader:
  /** The key for the top-level radio section in the configuration. */
  private val KeyRadio = "radio"

  /** Configuration key for the radio sources. */
  private val KeySources = "sources.source"

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
  private val KeyExclusionSets = "exclusion-sets.exclusion-set"

  /** Configuration key for the attribute defining an exclusion name. */
  private val KeyAttrName = "[@name]"

  /** Configuration key for a section with metadata exclusions. */
  private val KeyMetadataExclusions = "metadataExclusions.metadataExclusion"

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

  /** The key for a metadata exclusion pattern. */
  private val KeyPattern = "pattern"

  /** The key for the match context property of a metadata exclusion. */
  private val KeyMatchContext = "matchContext"

  /** The key for the resume mode property of a metadata exclusion. */
  private val KeyResumeMode = "resumeMode"

  /** The key for the check interval property of a metadata exclusion. */
  private val KeyCheckInterval = "checkInterval"

  /** The key for the section of the metadata config for a radio source. */
  private val KeyMetadataConfig = "metadata"

  /** The key for the pattern to extract song information from metadata. */
  private val KeySongPattern = "songPattern"

  /** The key for the section with resume intervals of a source. */
  private val KeyResumeIntervals = "resumeIntervals.resumeInterval"

  /**
    * The key for the section with the applicable intervals for metadata
    * exclusions.
    */
  private val KeyApplicable = "applicableAt.time"

  /** The key of the section defining the favorites. */
  private val KeyFavorites = "favorites.favorite"

  /** The key for referencing a radio source from a favorite. */
  private val KeyFavoriteSource = "sourceRef"

  /** The key defining the display name of a favorite. */
  private val KeyFavoriteDisplay = "displayName"

  /** The default match context value. */
  private val DefaultMatchContext = "Raw"

  /** The default resume mode. */
  private val DefaultResumeMode = "MetadataChange"

  /** The logger. */
  private val log = LogManager.getLogger(getClass)

  /**
    * Type definition for the data that is extracted for a radio source:
    *  - The name of the source.
    *  - The radio source itself.
    *  - The ranking of this source.
    *  - A sequence with interval queries defined for this source.
    */
  private type RadioSourceData = (String, RadioSource, Int, Seq[IntervalQuery])

  /**
    * Type definition for the data that is extracted for favorites:
    *  - A display name for the favorite.
    *  - The referenced radio source.
    */
  private type FavoriteData = (String, RadioSource)

  /**
    * Creates a new instance of ''RadioSourceConfig'' with the content of the
    * specified [[HierarchicalConfiguration]] object.
    *
    * @param config  the ''HierarchicalConfiguration''
    * @param rootKey the root key under which the source configuration is
    *                located
    * @return the new ''RadioSourceConfig''
    */
  def loadSourceConfig(config: HierarchicalConfiguration, rootKey: String = KeyRadio): RadioSourceConfig =
    val rootConfig = Try {
      config configurationAt rootKey
    } getOrElse new HierarchicalConfiguration

    val srcData = readSourcesFromConfig(rootConfig, rootKey)
    val sources = srcData map (t => (t._1, t._2))
    val favorites = readFavorites(rootConfig, sources)
    val exclusions = srcData map (t => (t._2, t._4))
    val ranking = srcData.map(t => (t._2, t._3)).toMap
    RadioSourceConfigImpl(sources, exclusions.toMap, ranking, favorites)

  /**
    * Creates a new instance of [[MetadataConfig]] with the content of the
    * specified [[HierarchicalConfiguration]] object.
    *
    * @param config  the ''HierarchicalConfiguration''
    * @param rootKey the root key under which the metadata configuration is
    *                located
    * @return the new [[MetadataConfig]]
    */
  def loadMetadataConfig(config: HierarchicalConfiguration, rootKey: String = KeyRadio): MetadataConfig =
    if config.getMaxIndex(rootKey) == 0 then
      val rootConfig = config.configurationAt(rootKey)
      MetadataConfigImpl(readMetadataExclusions(rootConfig), readMetadataConfigForSources(rootConfig))
    else MetadataConfig.Empty

  /**
    * Reads the defined radio sources from the configuration.
    *
    * @param config  the configuration to be processed
    * @param rootKey the root key of the source configuration
    * @return a sequence with the extracted radio sources: the name, the source
    *         itself, the ranking, the associated interval queries, the
    *         favorite index (-1 if the source is not a favorite), and the
    *         favorite name
    */
  private def readSourcesFromConfig(config: HierarchicalConfiguration, rootKey: String): Seq[RadioSourceData] =
    val namedExclusions = readNamedExclusions(config)
    val exclusionSets = readExclusionSets(config, namedExclusions)

    val sources = sourcesConfig(config) map { c =>
      val name = c.getString(KeySourceName)
      (name,
        RadioSource(c.getString(KeySourceURI),
          Option(c.getString(KeySourceExtension))),
        c.getInt(KeySourceRanking, DefaultRanking),
        readExclusionsSection(c, namedExclusions, exclusionSets)
      )
    }

    sources.sortWith(compareSources)

  /**
    * Returns a sequence with sub configurations for the valid radio source
    * declarations found in the given configuration.
    *
    * @param config the configuration
    * @return a sequence with sub configurations for source declarations
    */
  private def sourcesConfig(config: HierarchicalConfiguration): Seq[HierarchicalConfiguration] =
    config.configurationsAt(KeySources).asScala.filter { c =>
      c.containsKey(KeySourceName) && c.containsKey(KeySourceURI)
    }.toSeq

  /**
    * Parses the section with named exclusions.
    *
    * @param config the configuration
    * @return a map with named exclusion queries
    */
  private def readNamedExclusions(config: HierarchicalConfiguration): Map[String, IntervalQuery] =
    if config.getMaxIndex("") < 0 then Map.empty
    else readExclusions(config)
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
  private def readExclusionSets(config: HierarchicalConfiguration,
                                namedExclusions: Map[String, IntervalQuery]):
  Map[String, Seq[IntervalQuery]] =
    val setConfigs = config configurationsAt KeyExclusionSets
    setConfigs.asScala.filter(_.containsKey(KeyAttrName)).map { c =>
      val name = c.getString(KeyAttrName)
      val exclusions = readExclusionsSection(c, namedExclusions, Map.empty)
      (name, exclusions)
    }.toMap

  /**
    * Parses the exclusions definition from the given configuration. This
    * function can process an arbitrary section with interval query
    * definitions. The exact key to process can be specified.
    *
    * @param config        the sub configuration for the current source
    * @param keyExclusions the key to read the exclusions from
    * @return a sequence with all extracted interval queries and their optional
    *         names
    */
  private def readExclusions(config: HierarchicalConfiguration,
                             keyExclusions: String = KeyExclusions): Seq[(IntervalQuery, Option[String])] =
    val exConfigs = config configurationsAt keyExclusions
    exConfigs.asScala.foldLeft(List.empty[(IntervalQuery, Option[String])]) { (q, c) =>
      parseIntervalQuery(c) match
        case Some(query) =>
          val exclusionName = Option(c.getString(KeyAttrName))
          (IntervalQueries.cyclic(query), exclusionName) :: q
        case None => q
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
                                    exclusionSets: Map[String, Seq[IntervalQuery]]): Seq[IntervalQuery] =
    val inlineExclusions = readExclusions(config).map(_._1)
    val referencedExclusions = readReferences(config, KeyExclusionRef, namedExclusions)
    val referencedExclusionSets = readReferences(config, KeyExclusionSetRef, exclusionSets).flatten

    inlineExclusions ++ referencedExclusions ++ referencedExclusionSets

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
  private def compareSources(t1: RadioSourceData, t2: RadioSourceData): Boolean =
    if t1._3 != t2._3 then t1._3 > t2._3
    else t1._1 < t2._1

  /**
    * Tries to parse an interval query from an exclusion element in the
    * configuration.
    *
    * @param c the sub configuration for a single source
    * @return an option for the extracted interval query
    */
  private def parseIntervalQuery(c: Configuration): Option[IntervalQuery] =
    val minutes = parseRangeQuery(c, 60, KeyIntervalMinutes)(IntervalQueries.minutes)
    val hours = parseRangeQuery(c, 24, KeyIntervalHours)(IntervalQueries.hours)
    val days = parseWeekDayQuery(c)

    val parts = days :: hours :: minutes :: Nil
    parts.flatten.reduceOption(IntervalQueries.combine)

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
    IntervalQuery): Option[IntervalQuery] =
    def checkValue(value: Int): Boolean =
      value >= 0 && value <= max

    if c.containsKey(key + KeyAttrFrom) && c.containsKey(key + KeyAttrTo) then
      try
        val from = c.getInt(key + KeyAttrFrom)
        val to = c.getInt(key + KeyAttrTo)
        if checkValue(from) && checkValue(to) && from < to then
          Some(f(from, to))
        else
          log.warn(s"Invalid query parameters ($from, $to) for key $key.")
          None
      catch
        case e: ConversionException =>
          log.warn("Non parsable values in exclusion definition!", e)
          None
    else None

  /**
    * Parses an exclusion definition based on days of week.
    *
    * @param c the configuration
    * @return an option for the parsed query
    */
  private def parseWeekDayQuery(c: Configuration): Option[IntervalQuery] =
    val days = c.getStringArray(KeyIntervalDays)
    try
      val daySet = days.map(DayOfWeek.valueOf(_).getValue).toSet
      if daySet.nonEmpty then Some(IntervalQueries.weekDaySet(daySet))
      else None
    catch
      case i: IllegalArgumentException =>
        log.warn("Could not parse day-of-week query!", i)
        None

  /**
    * Reads a section with metadata exclusion at the given key. This can either
    * be the section with the global exclusions or a section specific to a
    * single source.
    *
    * @param config the configuration
    * @return the sequence with extracted metadata exclusions
    */
  private def readMetadataExclusions(config: HierarchicalConfiguration): Seq[MetadataExclusion] =
    config.configurationsAt(KeyMetadataExclusions).asScala.filter(_.containsKey(KeyPattern)).map { exclConf =>
      MetadataExclusion(pattern = Pattern.compile(exclConf.getString(KeyPattern)),
        matchContext = MatchContext.withName(exclConf.getString(KeyMatchContext, DefaultMatchContext)),
        resumeMode = ResumeMode.withName(exclConf.getString(KeyResumeMode, DefaultResumeMode)),
        checkInterval = exclConf.getInt(KeyCheckInterval).seconds,
        applicableAt = readExclusions(exclConf, KeyApplicable).map(_._1),
        name = Option(exclConf.getString(KeyAttrName)))
    }.toSeq

  /**
    * Reads the metadata configurations for all valid radio sources from the
    * given configuration. Returns a map using the radio source URIs as keys.
    *
    * @param config the configuration
    * @return a map with metadata configurations for all radio sources
    */
  private def readMetadataConfigForSources(config: HierarchicalConfiguration): Map[String, RadioSourceMetadataConfig] =
    sourcesConfig(config).map { srcConfig =>
      val srcUri = srcConfig.getString(KeySourceURI)
      val srcMetaConfig = readSourceMetadataConfig(srcConfig)
      srcUri -> srcMetaConfig
    }.toMap

  /**
    * Reads the metadata configuration of a specific radio source if it is
    * defined. Otherwise, result is an empty metadata configuration.
    *
    * @param srcConfig the sub configuration for the source
    * @return the [[RadioSourceMetadataConfig]] for this source
    */
  private def readSourceMetadataConfig(srcConfig: HierarchicalConfiguration): RadioSourceMetadataConfig =
    if srcConfig.getMaxIndex(KeyMetadataConfig) >= 0 then
      val metaConfig = srcConfig.configurationAt(KeyMetadataConfig)
      val optSongPattern = Option(metaConfig.getString(KeySongPattern)).map { s => Pattern.compile(s) }
      val exclusions = readMetadataExclusions(metaConfig)
      val resumeIntervals = readExclusions(metaConfig, KeyResumeIntervals).map(_._1)
      RadioSourceMetadataConfig(optSongPattern = optSongPattern,
        resumeIntervals = resumeIntervals,
        exclusions = exclusions)
    else MetadataConfig.EmptySourceConfig

  /**
    * Constructs the list of favorite radio stations from the given 
    * configuration. The given sequence of sources is used to map the favorites
    * to existing sources. For favorite declarations for which this fails, a
    * warning message is logged, and the declaration is ignored.
    *
    * @param config       the configuration to extract the favorites
    * @param namedSources a sequence with radio sources and their names
    * @return a sequence with the extracted favorites
    */
  private def readFavorites(config: HierarchicalConfiguration, namedSources: Seq[(String, RadioSource)]):
  Seq[FavoriteData] =
    val sourcesByName = namedSources.toMap
    val (validConfigs, invalidConfigs) = config.configurationsAt(KeyFavorites).asScala.partition { c =>
      sourcesByName.contains(c.getString(KeyFavoriteSource))
    }

    if invalidConfigs.nonEmpty then
      log.warn("Found {} invalid favorite declarations.", invalidConfigs.size)
    validConfigs.map { c =>
      val sourceName = c.getString(KeyFavoriteSource)
      val displayName = c.getString(KeyFavoriteDisplay, sourceName)
      (displayName, sourcesByName(sourceName))
    }.toSeq
end RadioSourceConfigLoader

/**
  * Internal implementation of the radio source config trait as a plain case
  * class.
  *
  * @param namedSources  the list with sources
  * @param exclusionsMap the map with exclusions
  * @param rankingMap    a map with source rankings
  * @param favorites     the list with favorite sources
  */
private case class RadioSourceConfigImpl(override val namedSources: Seq[(String, RadioSource)],
                                         exclusionsMap: Map[RadioSource, Seq[IntervalQuery]],
                                         rankingMap: Map[RadioSource, Int],
                                         override val favorites: Seq[(String, RadioSource)])
  extends RadioSourceConfig:
  override def ranking(source: RadioSource): Int =
    rankingMap.getOrElse(source, DefaultRanking)

  override def exclusions(source: RadioSource): Seq[IntervalQuery] = exclusionsMap.getOrElse(source, Seq.empty)

/**
  * Internal implementation of the [[MetadataConfig]] trait as a plain case
  * class.
  *
  * @param exclusions            the sequence with global metadata exclusions
  * @param sourceMetadataConfigs a map with metadata configs for sources
  */
private case class MetadataConfigImpl(override val exclusions: Seq[MetadataExclusion],
                                      sourceMetadataConfigs: Map[String, RadioSourceMetadataConfig])
  extends MetadataConfig:
  override def metadataSourceConfig(source: RadioSource): RadioSourceMetadataConfig =
    sourceMetadataConfigs.getOrElse(source.uri, super.metadataSourceConfig(source))
