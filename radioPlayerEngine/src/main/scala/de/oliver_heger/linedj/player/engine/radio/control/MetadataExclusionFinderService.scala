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

import de.oliver_heger.linedj.player.engine.interval.IntervalTypes
import de.oliver_heger.linedj.player.engine.radio.CurrentMetadata
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.{MatchContext, MetadataExclusion, RadioSourceMetadataConfig}
import de.oliver_heger.linedj.player.engine.radio.control.MetadataExclusionFinderService.MetadataExclusionFinderResponse

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

object MetadataExclusionFinderService {
  /**
    * A data class defining the response of [[MetadataExclusionFinderService]]
    * for a single request to find an exclusion for metadata.
    *
    * @param result the actual result; the found exclusion if any
    * @param seqNo  the sequence number passed to the request
    */
  case class MetadataExclusionFinderResponse(result: Option[MetadataExclusion], seqNo: Int)
}

/**
  * Definition of a service that finds matching [[MetadataExclusion]]s in a
  * given metadata object. This is used to determine whether a radio source
  * should be disabled based on its current metadata information.
  */
trait MetadataExclusionFinderService {
  /**
    * Tries to find a [[MetadataExclusion]] from the given configurations that
    * matches the provided metadata asynchronously.
    *
    * @param metadataConfig the global metadata configuration
    * @param sourceConfig   the configuration for the current radio source
    * @param metadata       the metadata to check
    * @param refTime        the reference time to evaluate the ''applicableAt''
    *                       field of exclusions
    * @param seqNo          a sequence number
    * @param ec             the execution context
    * @return a (successful) ''Future'' with the response
    */
  def findMetadataExclusion(metadataConfig: MetadataConfig,
                            sourceConfig: RadioSourceMetadataConfig,
                            metadata: CurrentMetadata,
                            refTime: LocalDateTime,
                            seqNo: Int)
                           (implicit ec: ExecutionContext): Future[MetadataExclusionFinderResponse]
}

/**
  * A default implementation of the [[MetadataExclusionFinderService]] trait.
  *
  * @param intervalsService the service to evaluate interval queries
  */
class MetadataExclusionFinderServiceImpl(val intervalsService: EvaluateIntervalsService)
  extends MetadataExclusionFinderService {
  def findMetadataExclusion(metadataConfig: MetadataConfig,
                            sourceConfig: RadioSourceMetadataConfig,
                            metadata: CurrentMetadata,
                            refTime: LocalDateTime,
                            seqNo: Int)
                           (implicit ec: ExecutionContext): Future[MetadataExclusionFinderResponse] = {
    lazy val (optArtist, optSong) = extractSongData(sourceConfig, metadata)

    val matchingExclusions = (sourceConfig.exclusions ++ metadataConfig.exclusions).filter { exclusion =>
      val optData = exclusion.matchContext match {
        case MatchContext.Title => Some(metadata.title)
        case MatchContext.Artist => optArtist
        case MatchContext.Song => optSong
        case MatchContext.Raw => Some(metadata.data)
      }
      optData exists { data => matches(exclusion.pattern, data) }
    }

    if (matchingExclusions.isEmpty) Future.successful(MetadataExclusionFinderResponse(None, seqNo))
    else matchingExclusions.find(!_.hasTimeRestrictions) match {
      case result@Some(_) =>
        Future.successful(MetadataExclusionFinderResponse(result, seqNo))

      case None =>
        val queryResults = matchingExclusions.map { exclusion =>
          intervalsService.evaluateIntervals(exclusion.applicableAt, refTime, 0)
            .map(exclusion -> _)
        }
        Future.sequence(queryResults).map { sequence =>
          sequence.find { t => t._2.result.isInstanceOf[IntervalTypes.Inside] }
            .map(_._1)
        }.map(MetadataExclusionFinderResponse(_, seqNo))
    }
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
