/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.interval

import java.time.LocalDateTime

/**
  * An object defining the central data types used by the interval framework.
  */
object IntervalTypes {
  type IntervalQuery = LocalDateTime => IntervalQueryResult

  /**
    * The result type of an interval query. An instance of this trait is
    * returned by a check for the relation of a date to a time interval.
    * There are concrete subtypes providing specific information.
    */
  sealed trait IntervalQueryResult

  /**
    * The result ''before'': the queried date lies before the start of a time
    * interval. It can be found out when the interval starts.
    *
    * @param start the earliest start time of the interval
    */
  case class Before(start: LazyDate) extends IntervalQueryResult

  /**
    * The result ''inside'': the queried data is inside the time interval. It
    * is possible to find out how long the interval is going to last.
    *
    * @param until     the end date of the interval
    */
  case class Inside(until: LazyDate) extends IntervalQueryResult

  /**
    * The result ''after'': the queried date lies after the time interval.
    * A result contains a function to cycle the current date to a future
    * position, so that the interval query will return a ''Before'' result for
    * it. This is useful when combining queries or making queries cyclic.
    *
    * @param cycle the function to shift the current date to another cycle
    */
  case class After(cycle: LocalDateTime => LocalDateTime) extends IntervalQueryResult

}
