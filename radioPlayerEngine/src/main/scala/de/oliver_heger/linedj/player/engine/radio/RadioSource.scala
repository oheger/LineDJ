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

package de.oliver_heger.linedj.player.engine.radio

import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.IntervalQuery

import scala.collection.immutable.Seq

object RadioSource:
  /**
    * A ranking function for radio sources.
    *
    * This is used by some parts of the radio player engine to select a
    * specific source if there are multiple options; for instance, if the
    * desired source cannot be played due to an error, and now a replacement
    * source is needed.
    *
    * The ranking function assigns a numeric value to a radio source. Sources
    * with a higher ranking are preferred over sources with a lower ranking. It
    * is up to a concrete application to map this to specific numeric values.
    */
  type Ranking = RadioSource => Int

  /**
    * Alias for a function that returns a sequence of [[IntervalQuery]]s for a
    * given [[RadioSource]]. This function can be used to check whether a
    * specific source can be played at a given point in time.
    */
  type ExclusionQueryFunc = RadioSource => Seq[IntervalQuery]

  /**
    * A dummy ranking function that assigns the same numeric value - zero - to
    * all radio sources. This is useful if all sources should be treated the
    * same way and there is no prioritization.
    */
  final val NoRanking: Ranking = _ => 0

  /**
    * A dummy function for source exclusions that always returns an empty
    * sequence with interval queries.
    */
  final val NoExclusions: ExclusionQueryFunc = _ => Seq.empty

/**
  * A data class identifying a radio stream to be played.
  *
  * Instances of this class are processed by a radio player. When this message
  * is received, playback of the current stream - if any - stops, and a source
  * reader actor is created for the new stream. The default extension is of use
  * if the resolved stream URI does not have an extension. However, this is
  * required to select the correct audio codec when creating the playback
  * context.
  *
  * @param uri              the URI of the stream to be played
  * @param defaultExtension an optional default extension
  */
case class RadioSource(uri: String, defaultExtension: Option[String] = None):
  /**
    * Returns a URI for this radio source that has a proper file extension.
    * If a default extension is provided, this function makes sure that the
    * resulting URI ends on this extension.
    *
    * @return a radio source URI with a correct file extension
    */
  def uriWithExtension: String =
    defaultExtension match
      case Some(ext) if !uri.endsWith("." + ext) => s"$uri.$ext"
      case _ => uri
