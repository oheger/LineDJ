/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.platform.ui

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

/**
  * A module providing some functions to generate texts on the UI based on
  * elapsed time.
  */
object TextTimeFunctions:
  /**
    * Type alias of a function that calculates a string based on an elapsed
    * time. The function expects the elapsed time as argument and returns the
    * corresponding string.
    */
  type TextTimeFunc = FiniteDuration => String

  /** Constant for a duration of length 0. */
  private val DurationZero = FiniteDuration(0, TimeUnit.MILLISECONDS)

  /** The default separator to use for rotating text. */
  private val DefaultRotationSeparator = " * "

  /**
    * Returns a [[TextTimeFunc]] that generates a formatted time using
    * [[DurationTransformer]].
    *
    * @return the function generating a formatted time
    */
  def formattedTime(): TextTimeFunc = time =>
    DurationTransformer.formatDuration(time.toMillis)

  /**
    * Returns a [[TextTimeFunc]] that generates a rotating text based on
    * elapsed time. This can be used for instance to display a (longer) song
    * title in a field in the UI, letting the text scroll through the field.
    * If the text exceeds the specified maximum length, rotation is active.
    *
    * @param text       the text to rotate
    * @param maxLen     the maximum text length that can be displayed without
    *                   rotation
    * @param relativeTo a duration that is subtracted from the passed in
    *                   duration
    * @param timeUnit   the time unit to use for calculating the rotation
    *                   offset
    * @param scale      an additional scale factor; the elapsed time is
    *                   multiplied with this factor
    * @param separator  the separator when wrapping the text
    * @return the function generating rotated text
    */
  def rotateText(text: String,
                 maxLen: Int,
                 relativeTo: FiniteDuration = DurationZero,
                 timeUnit: TimeUnit = TimeUnit.SECONDS,
                 scale: Double = 1.0,
                 separator: String = DefaultRotationSeparator): TextTimeFunc =
    if text.length <= maxLen then _ => text
    else
      val rotateText = text + separator + text
      val textLength = text.length + separator.length
      time => {
        val ofs = math.round((time - relativeTo).toUnit(timeUnit) * scale).toInt % textLength
        rotateText.substring(ofs, ofs + maxLen)
      }

  /**
    * Generates a new [[TextTimeFunc]] based on a given one by appending a
    * suffix to the result of the other function.
    *
    * @param suffix the suffix string to append
    * @param f      the original [[TextTimeFunc]]
    * @return the function with the appended suffix
    */
  def withSuffix(suffix: String)(f: TextTimeFunc): TextTimeFunc = time => s"${f(time)}$suffix"
