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

import de.oliver_heger.linedj.player.engine.interval.IntervalQueries.hours
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.IntervalQuery
import de.oliver_heger.linedj.player.engine.radio.RadioSource
import de.oliver_heger.linedj.player.engine.radio.config.RadioSourceConfig

import scala.collection.immutable.Seq

/**
  * A module providing functionality needed by tests that have to deal with
  * radio sources and exclusion queries. The module allows creating a test
  * sources configuration based on a map with sources and their exclusion
  * queries.
  */
object RadioSourceConfigTestHelper:
  /**
    * A default map containing some radio sources and test exclusion queries.
    * This can be used by test cases if the exact exclusion queries do not
    * matter.
    */
  final val TestSourcesQueryMap = createDefaultSourceQueries()

  /**
    * Creates a test radio source object whose URI is derived from the given
    * index.
    *
    * @param idx the index of the radio source
    * @return the test radio source with this index
    */
  def radioSource(idx: Int): RadioSource =
    RadioSource("TestRadioSource_" + idx)

  /**
    * Creates a configuration for radio sources based on the given map with
    * radio sources and their exclusion queries. Optionally, a specific ranking
    * function can be provided as well.
    *
    * @param queryMap the map with sources and their exclusions
    * @param rankingF a ranking function for sources
    * @return the config about radio sources
    */
  def createSourceConfig(queryMap: Map[RadioSource, Seq[IntervalQuery]],
                         rankingF: RadioSource => Int = rankBySourceIndex): RadioSourceConfig =
    val namedSourcesList = queryMap.keys.map { src => (src.uri, src) }.toSeq

    new RadioSourceConfig:
      override val namedSources: Seq[(String, RadioSource)] = namedSourcesList

      override def exclusions(source: RadioSource): Seq[IntervalQuery] =
        queryMap.getOrElse(source, Seq.empty)

      override def ranking(source: RadioSource): Int = rankingF(source)

  /**
    * The default ranking function used by the configuration created by this
    * test helper. It expects that the radio source was created by the
    * [[radioSource]] function and extracts the numeric index.
    *
    * @param source the radio source
    * @return a ranking for this source
    */
  def rankBySourceIndex(source: RadioSource): Int =
    source.uri.substring(source.uri.lastIndexOf('_') + 1).toInt

  /**
    * Creates a map with some radio sources and test exclusion queries to be
    * used by test cases.
    *
    * @return the map with sources and their exclusion queries
    */
  private def createDefaultSourceQueries(): Map[RadioSource, Seq[IntervalQuery]] =
    Map(radioSource(1) -> List(hours(1, 2)),
      radioSource(2) -> List(hours(2, 3)),
      radioSource(3) -> List(hours(4, 5)))
