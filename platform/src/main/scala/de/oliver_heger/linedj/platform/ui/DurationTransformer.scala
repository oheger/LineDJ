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

package de.oliver_heger.linedj.platform.ui

import net.sf.jguiraffe.transform.{Transformer, TransformerContext}

import scala.annotation.tailrec

/**
  * Companion object for ''DurationTransformer''.
  *
  * This object defines methods for formatting a duration. These can be directly
  * used by client code without the need to create an instance. The transformer
  * implementation is useful when used within the UI declaration.
  */
object DurationTransformer {
  /** The representation of an undefined duration. */
  private val UndefinedRepresentation = "?"

  /** Constant for the number of milliseconds per second. */
  private val MILLIS = 1000.0

  /** Constant for the number of seconds per hour. */
  private val SECS_PER_HOUR = 60 * 60

  /** Constant for the seconds per minute. */
  private val SECS_PER_MINUTE = 60

  /** Constant for the seconds of a day. */
  private val SECS_PER_DAY = 24 * SECS_PER_HOUR

  /** Constant for the duration buffer size. */
  private val DURATION_BUF_SIZE = 16

  /** A list with the durations corresponding to the supported time fields. */
  private val DURATION_FIELDS = List(SECS_PER_DAY, SECS_PER_HOUR, SECS_PER_MINUTE, 1)

  /**
    * Returns a string representation for the specified duration.
    *
    * @param duration the duration as long (in milliseconds)
    * @return the formatted duration as string
    */
  def formatDuration(duration: Long): String = {
    val buf = new StringBuilder(DURATION_BUF_SIZE)
    val secs = math.round(duration / MILLIS)
    val hours = secs / SECS_PER_HOUR
    if (hours > 0) {
      buf.append(hours).append(':')
    }
    val mins = (secs % SECS_PER_HOUR) / SECS_PER_MINUTE
    if (mins < 10 && hours > 0) {
      buf.append('0')
    }
    buf.append(mins).append(':')
    val remainingSecs = secs % SECS_PER_MINUTE
    if (remainingSecs < 10) {
      buf.append('0')
    }
    buf.append(remainingSecs)
    buf.toString()
  }

  /**
    * Returns a string representation for the specified duration that can cover
    * multiple days. This function produces a different string representation
    * than ''formatDuration()'', which is more suitable for very long
    * durations. The resulting string contains fields for days, hours, minutes,
    * and seconds, each labeled with the corresponding strings. Fields with the
    * value 0 are omitted.
    *
    * @param duration   the duration as long (in milliseconds)
    * @param txtDays    the label for the days field
    * @param txtHours   the label for the hours field
    * @param txtMinutes the label for the minutes field
    * @param txtSeconds the label for the seconds field
    * @return the formatted duration as string
    */
  def formatLongDuration(duration: Long, txtDays: String, txtHours: String, txtMinutes: String, txtSeconds: String):
  String = {
    @tailrec
    def generateDurationField(buf: StringBuilder, currentDuration: Long, fields: List[Int], units: List[String]):
    String =
      fields match {
        case h :: t =>
          val value = currentDuration / h
          if (value > 0) {
            if (buf.nonEmpty) {
              buf.append(' ')
            }
            buf.append(value).append(units.head)
          }
          generateDurationField(buf, currentDuration % h, t, units.tail)
        case _ =>
          buf.toString()
      }

    val units = List(txtDays, txtHours, txtMinutes, txtSeconds)
    generateDurationField(new StringBuilder(DURATION_BUF_SIZE), duration / MILLIS.toLong, DURATION_FIELDS, units)
  }
}

/**
  * A special transformer implementation which formats duration values.
  *
  * This transformer is used to display the duration of songs in the UI. The
  * duration is stored internally as long value representing the number of
  * milliseconds a song lasts. This transformer produces a human-readable
  * representation in the form HH:mm:ss.
  */
class DurationTransformer extends Transformer {

  import DurationTransformer._

  override def transform(o: Any, ctx: TransformerContext): AnyRef = {
    o match {
      case n: Number if n.longValue() >= 0 =>
        formatDuration(n.longValue())

      case _ => UndefinedRepresentation
    }
  }
}
