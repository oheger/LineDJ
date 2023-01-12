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

package de.oliver_heger.linedj.player.engine.radio

import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.IntervalQuery

/**
  * A trait allowing access to configuration information about radio sources.
  *
  * Instances of this class contain all information available about radio
  * sources, i.e. the source declarations (including name and URI) plus
  * optional exclusions for each source.
  *
  * An exclusion is an interval in which a radio source should not be played;
  * for instance, a station might send commercials for each half or full hour.
  * Then [[IntervalQuery]] instances can be defined excluding these times.
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
