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

import java.time.LocalDateTime
import scala.concurrent.duration._
import scala.util.Try

/**
  * An object defining common functionality used in this package.
  */
package object control {
  /**
    * Calculates the duration between the given times (with second
    * granularity). Handles also large differences gracefully. This is needed,
    * since the range of [[FiniteDuration]] is limited to ~292 years. If an
    * overflow is noticed, the provided maximum duration is returned.
    *
    * @param startTime   the start time
    * @param endTime     the end time
    * @param maxDuration the maximum duration to return
    * @return the duration between these times (or the maximum duration in case
    *         of an overflow)
    */
  def durationBetween(startTime: LocalDateTime, endTime: LocalDateTime, maxDuration: FiniteDuration): FiniteDuration =
    Try {
      java.time.Duration.between(startTime, endTime).toSeconds.seconds
    } getOrElse maxDuration
}
