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
  * Definition of a trait which manipulates the current playlist.
  *
  * Concrete implementations of this trait can be used to execute certain
  * operations (e.g. clean, reorder, etc.) on the current playlist.
  * [[PlaylistController]] defines a method which accepts a
  * ''PlaylistManipulator'' and executes it on the current playlist.
  */
trait PlaylistManipulator:
  /**
    * Updates the playlist managed by the given ''TableHandler''. Concrete
    * implementations can access the handler's table model and manipulate it.
    * They should also remember to call the correct update method on the
    * handler afterwards.
    * @param context the ''PlaylistSelectionContext''
    */
  def updatePlaylist(context: PlaylistSelectionContext): Unit

  /**
    * Returns a flag whether the manipulation represented by this object is
    * currently available. This is used to enable or disable the associated
    * action.
    * @param context the ''PlaylistSelectionContext''
    * @return a flag whether this manipulation is available
    */
  def isEnabled(context: PlaylistSelectionContext): Boolean
