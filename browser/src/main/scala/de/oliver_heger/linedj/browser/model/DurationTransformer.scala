/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.browser.model

import net.sf.jguiraffe.transform.{Transformer, TransformerContext}

/**
 * Companion object for ''DurationTransformer''.
 *
 * This object defines a method for formatting a duration. This can be directly
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

  /** Constant for the duration buffer size. */
  private val DURATION_BUF_SIZE = 16

  /**
   * Returns a string representation for the specified duration.
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
