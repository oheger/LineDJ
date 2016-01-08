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

package de.oliver_heger.linedj.pleditor.ui.playlist

/**
  * A task implementation which allows moving the selected items in the
  * playlist down by one position.
  *
  * @param controller the ''PlaylistController''
  */
class PlaylistMoveDownTask(controller: PlaylistController) extends
AbstractPlaylistManipulationTask(controller) with PlaylistManipulator {

  /**
    * @inheritdoc This implementation swaps all selected elements with their
    *             successors.
    */
  override def updatePlaylist(context: PlaylistSelectionContext): Unit = {
    val model = context.tableHandler.getModel
    val orderIndices = context.selectedIndices sortWith (_ > _)
    for (idx <- orderIndices) {
      val t = model.get(idx + 1)
      model.set(idx + 1, model.get(idx))
      model.set(idx, t)
    }
    context.tableHandler.rowsUpdated(context.minimumSelectionIndex,
      context.maximumSelectionIndex + 1)
    context.tableHandler.setSelectedIndices(context.selectedIndices map (_ + 1))
  }

  /**
    * @inheritdoc This implementation returns '''true''' if and only if there
    *             is a selection, but the last element is not selected.
    */
  override def isEnabled(context: PlaylistSelectionContext): Boolean =
    context.hasSelection && !context.isLastElementSelected
}
