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

/**
 * A trait for resolving the names of unknown references.
 *
 * Meta data for a song may be incomplete, for instance information about the
 * artist or the album of a song may be missing. What to display in this case
 * is probably not constant, but can depend on the language of the current
 * user. This trait defines methods for querying names of unknown references.
 * Concrete implementations can use different strategies for looking up such
 * names, e.g. via a resource manager.
 */
trait UnknownNameResolver {
  /** The name to be returned for an unknown artist. */
  val unknownArtistName: String

  /** The name to be returned for an unknown album. */
  val unknownAlbumName: String
}
