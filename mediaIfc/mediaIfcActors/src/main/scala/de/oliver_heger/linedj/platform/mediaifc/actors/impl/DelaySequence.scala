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

package de.oliver_heger.linedj.platform.mediaifc.actors.impl

/**
 * A trait defining a sequence generator that produces delay values.
 *
 * This is used when connecting to a remote actor. At first lookups are made
 * with a higher frequency. If these fail, the intervals between the lookups
 * are increased to reduce network traffic. This trait just defines how delay
 * values can be obtained (in a functional way). Concrete implementations can
 * produce specific sequences.
 */
trait DelaySequence:
  /**
   * Determines the next value in the delay sequence and returns an object with
   * the updated state. The original object is not manipulated.
   * @return a tuple with the delay value and the next state of the sequence
   */
  def nextDelay: (Int, DelaySequence)
