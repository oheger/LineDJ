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

package de.oliver_heger.linedj.player.engine.radio.config

import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.IntervalQuery
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.MatchContext.MatchContext
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.MetadataExclusion
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.ResumeMode.ResumeMode

import java.util.regex.Pattern
import scala.concurrent.duration._

object MetadataConfig {
  /**
    * The name of the group in the regular expression to extract song
    * information from metadata that represents the song title.
    */
  final val SongTitleGroup = "title"

  /**
    * The name of the group in the regular expression to extract song
    * information from metadata that represents the artist.
    */
  final val ArtistGroup = "artist"

  /**
    * An enumeration defining the exact part of metadata where an exclusion
    * matches.
    */
  object MatchContext extends Enumeration {
    type MatchContext = Value

    /** The song title is matched. */
    val Song: Value = Value

    /** The artist name is matched. */
    val Artist: Value = Value

    /**
      * The match is on the current stream title. This typically consists of
      * both the song and the artist if a song is played. Otherwise, arbitrary
      * data could be in the title, like the name of the program, contact
      * information, etc.
      */
    val Title: Value = Value

    /**
      * The match is on the full metadata that may contain other fields in
      * addition to the stream title field which typically contains the
      * currently played song.
      */
    val Raw: Value = Value
  }

  /**
    * An enumeration listing the supported modes for resuming after a metadata
    * exclusion. This determines the necessary changes on metadata to consider
    * an exclusion to be finished.
    */
  object ResumeMode extends Enumeration {
    type ResumeMode = Value

    /**
      * The exclusion ends when there is an arbitrary change on the metadata.
      */
    val MetadataChange: Value = Value

    /**
      * The exclusion ends when another song is detected. This does not include
      * a change in metadata that is not recognized as a song.
      */
    val NextSong: Value = Value
  }

  /**
    * A data class describing a metadata exclusion.
    *
    * The class contains a regular expression pattern that is matched against
    * a defined part of the metadata of the current radio stream. If there is a
    * match, the current radio source is disabled. It is then checked
    * periodically whether the condition defined by the resume mode is
    * fulfilled.
    *
    * @param pattern       the pattern to match
    * @param matchContext  the part of the metadata where to match
    * @param resumeMode    the resume mode
    * @param checkInterval the periodic check interval
    * @param name          an optional name for this exclusion
    */
  case class MetadataExclusion(pattern: Pattern,
                               matchContext: MatchContext,
                               resumeMode: ResumeMode,
                               checkInterval: FiniteDuration,
                               name: Option[String])

  /**
    * A data class that represents the metadata configuration for a specific
    * radio source.
    *
    * A source can have a specific regular expression to extract the song title
    * and artist from the stream title field. To associate these properties,
    * the expression must define two named groups as specified by the constants
    * [[SongTitleGroup]], and [[ArtistGroup]]. If this expression is undefined,
    * the whole title is considered to be both the song title and the artist.
    *
    * It is possible to define multiple ''resume intervals'' for a radio
    * source. These are known times at which metadata exclusions typically end,
    * such as at a full hour when news are starting. Such intervals impact the
    * check interval of an exclusion (another check is scheduled when the next
    * resume interval starts) and also the [[ResumeMode]] (every change in
    * metadata then leads to an end of the exclusion).
    *
    * @param optSongPattern  optional pattern to extract the current song
    * @param resumeIntervals the resume intervals
    * @param exclusions      exclusions specific to this radio source
    */
  case class RadioSourceMetadataConfig(optSongPattern: Option[Pattern] = None,
                                       resumeIntervals: Seq[IntervalQuery] = Seq.empty,
                                       exclusions: Seq[MetadataExclusion] = Seq.empty)

  /**
    * Constant for a [[MetadataConfig]] instance that uses only default values.
    * This means that this instance does not define any exclusions.
    */
  final val Empty: MetadataConfig = MetadataConfig()

  /**
    * Constant for a [[RadioSourceMetadataConfig]] instance that uses only
    * default values. This instance does not contain any information that
    * could be used to exclude a corresponding radio source because of its
    * metadata.
    */
  final val EmptySourceConfig = RadioSourceMetadataConfig()
}

/**
  * A data class defining the global configuration for metadata exclusions.
  *
  * @param exclusions   a list of global exclusions that are applied to all
  *                     radio sources
  * @param checkTimeout timeout for checks whether an exclusion ends
  */
case class MetadataConfig(exclusions: Seq[MetadataExclusion] = Seq.empty,
                          checkTimeout: FiniteDuration = 30.seconds)
