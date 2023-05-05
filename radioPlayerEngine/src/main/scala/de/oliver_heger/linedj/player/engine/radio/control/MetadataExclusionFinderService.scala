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

package de.oliver_heger.linedj.player.engine.radio.control

import de.oliver_heger.linedj.player.engine.radio.CurrentMetadata
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.{MatchContext, MetadataExclusion, RadioSourceMetadataConfig}

import java.util.regex.{Matcher, Pattern}

/**
  * Definition of a service that finds matching [[MetadataExclusion]]s in a
  * given metadata object. This is used to determine whether a radio source
  * should be disabled based on its current metadata information.
  */
trait MetadataExclusionFinderService {
  /**
    * Tries to find a [[MetadataExclusion]] from the given configurations that
    * matches the provided metadata.
    *
    * @param metadataConfig the global metadata configuration
    * @param sourceConfig   the configuration for the current radio source
    * @param metadata       the metadata to check
    * @return an ''Option'' with a matched exclusion
    */
  def findMetadataExclusion(metadataConfig: MetadataConfig,
                            sourceConfig: RadioSourceMetadataConfig,
                            metadata: CurrentMetadata): Option[MetadataExclusion]
}

/**
  * A default implementation of the [[MetadataExclusionFinderService]] trait.
  */
private object MetadataExclusionFinderServiceImpl extends MetadataExclusionFinderService {
  def findMetadataExclusion(metadataConfig: MetadataConfig,
                            sourceConfig: RadioSourceMetadataConfig,
                            metadata: CurrentMetadata): Option[MetadataExclusion] = {
    lazy val (optArtist, optSong) = extractSongData(sourceConfig, metadata)

    (sourceConfig.exclusions ++ metadataConfig.exclusions).find { exclusion =>
      val optData = exclusion.matchContext match {
        case MatchContext.Title => Some(metadata.title)
        case MatchContext.Artist => optArtist
        case MatchContext.Song => optSong
        case MatchContext.Raw => Some(metadata.data)
      }
      optData exists { data => matches(exclusion.pattern, data) }
    }
  }

  /**
    * Checks whether the given pattern matches the input string.
    *
    * @param pattern the pattern
    * @param input   the input string
    * @return a flag whether this is a match
    */
  def matches(pattern: Pattern, input: String): Boolean = getMatch(pattern, input).isDefined

  /**
    * Tries to match the given input against the pattern and returns an
    * ''Option'' with the [[Matcher]] if a match was found.
    *
    * @param pattern the pattern
    * @param input   the input string
    * @return an ''Option'' with the matcher
    */
  private def getMatch(pattern: Pattern, input: String): Option[Matcher] = {
    val matcher = pattern.matcher(input)
    if (matcher.matches()) Some(matcher) else None
  }

  /**
    * Tries to extract the song title and artist from the given metadata. If
    * the radio source defines a corresponding extraction pattern, it is
    * applied and evaluated. Otherwise, exclusions will match on the whole
    * stream title.
    *
    * @param sourceConfig the configuration for the current radio source
    * @param metadata     the metadata
    * @return a pair with the optional extracted artist and song title
    */
  private def extractSongData(sourceConfig: RadioSourceMetadataConfig,
                              metadata: CurrentMetadata): (Option[String], Option[String]) =
    sourceConfig.optSongPattern match {
      case Some(pattern) =>
        getMatch(pattern, metadata.title).map { matcher =>
          (Some(matcher.group(MetadataConfig.ArtistGroup)), Some(matcher.group(MetadataConfig.SongTitleGroup)))
        } getOrElse ((None, None))
      case None =>
        (Some(metadata.title), Some(metadata.title))
    }
}
