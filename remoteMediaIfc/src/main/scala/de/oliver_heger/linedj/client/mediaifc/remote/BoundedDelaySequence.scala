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

package de.oliver_heger.linedj.client.mediaifc.remote

/**
 * An implementation of [[DelaySequence]] which produces an increasing sequence
 * of delay values with a configurable bound.
 *
 * The sequence is increased using a configurable step value until the maximum
 * value is reached. Afterwards, the sequence remains constant.
 *
 * @param maximum the upper bound for this sequence
 * @param step the step for increasing sequence values
 * @param current the current value in the sequence
 */
class BoundedDelaySequence(val maximum: Int, val step: Int, current: Int) extends DelaySequence {
  /** The validated current value of this sequence state. */
  val currentValue = math.min(maximum, current)

  /**
   * Determines the next value in the delay sequence and returns an object with
   * the updated state. The original object is not manipulated.
   * @return a tuple with the delay value and the next state of the sequence
   */
  override def nextDelay: (Int, DelaySequence) = {
    (currentValue, if (currentValue == maximum) this
    else new BoundedDelaySequence(maximum, step, currentValue + step))
  }
}
