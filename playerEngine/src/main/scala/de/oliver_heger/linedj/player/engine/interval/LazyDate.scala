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

package de.oliver_heger.linedj.player.engine.interval

import java.time.LocalDateTime

/**
  * A holder class of a date which is evaluated lazily.
  *
  * This class needed mainly because case classes do not support by-name
  * arguments. Therefore, dates to be evaluated lazily are stored in instances
  * of this class.
  *
  * @param ref the reference to the date managed by this instance
  */
class LazyDate(ref: => LocalDateTime):
  /**
    * The value of this ''LazyDate'', i.e. the wrapped date.
    */
  lazy val value = ref
