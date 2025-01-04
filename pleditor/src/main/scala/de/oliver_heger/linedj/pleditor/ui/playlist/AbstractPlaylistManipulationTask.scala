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

package de.oliver_heger.linedj.pleditor.ui.playlist

/**
  * An abstract base class for action tasks which manipulate the current
  * playlist.
  *
  * This base class contains logic to invoke the [[PlaylistController]].
  * Concrete subclasses need to implement the actual playlist manipulation
  * operation.
  *
  * @param controller the ''PlaylistController''
  */
abstract class AbstractPlaylistManipulationTask(val controller: PlaylistController) extends
Runnable:
  this: PlaylistManipulator =>

  /**
    * This implementation invokes the ''PlaylistController'' and triggers an
    * update of the playlist.
    */
  override def run(): Unit =
    controller updatePlaylist this
