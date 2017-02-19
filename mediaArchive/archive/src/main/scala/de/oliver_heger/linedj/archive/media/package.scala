/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archive

import java.util.UUID

/**
  * Package object for the ''media'' package.
  */
package object media {
  /**
    * The ID for the local archive component. The ID contains a random UUID,
    * so that multiple archive components can share their data without
    * interfering with each other.
    */
  val ArchiveComponentID: String = "local_" + UUID.randomUUID()
}
