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

package de.oliver_heger.splaya.media

/**
 * A trait defining meta data of a medium.
 *
 * This trait defines some properties with information about a medium. The data
 * which can be queried from an instance can be used e.g. in a UI when
 * presenting an overview over the media currently available.
 */
trait MediumInfo {
  /** The name of the represented medium. */
  val name: String

  /** An additional description about the represented medium. */
  val description: String

  /**
   * The root URI of the represented medium. This information is typically
   * transient; it is determined when the medium is loaded. It merely serves
   * informational purpose.
   */
  val mediumURI: String
}
