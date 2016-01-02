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

package de.oliver_heger.linedj.pleditor.playlist

/**
  * A task implementations which removes the selected items from the playlist.
  */
class PlaylistRemoveItemsTask(controller: PlaylistController) extends
AbstractPlaylistManipulationTask(controller) with PlaylistManipulator {

  /**
    * @inheritdoc This implementation removes all selected indices from the
    *             table model.
    */
  override def updatePlaylist(context: PlaylistSelectionContext): Unit = {
    val sortedSelection = context.selectedIndices sortWith (_ > _)
    val model = context.tableHandler.getModel
    sortedSelection foreach model.remove
    context.tableHandler.tableDataChanged()
  }

  /**
    * @inheritdoc This implementation returns '''true''' if and only if a
    *             selection is present.
    */
  override def isEnabled(context: PlaylistSelectionContext): Boolean = context.hasSelection
}
