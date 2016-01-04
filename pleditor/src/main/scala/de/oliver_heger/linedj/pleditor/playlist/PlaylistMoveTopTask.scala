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
  * A task implementation which allows moves selected playlist items to the
  * top of the playlist.
  *
  * @param controller the ''PlaylistController''
  */
class PlaylistMoveTopTask(controller: PlaylistController) extends
AbstractPlaylistManipulationTask(controller) with PlaylistManipulator {

  /**
    * @inheritdoc This implementation creates a partition of the table model
    *             consisting of selected and not selected elements. The
    *             resulting model is then constructed by concatenating these
    *             partitions.
    */
  override def updatePlaylist(context: PlaylistSelectionContext): Unit = {
    import scala.collection.JavaConverters._
    val selection = context.selectedIndices.toSet
    val model = context.tableHandler.getModel
    val indexedModel = model.asScala.toList.zipWithIndex
    val partition = indexedModel.partition(e => selection contains e._2)

    model.clear()
    partition._1 foreach (e => model.add(e._1))
    partition._2 foreach (e => model.add(e._1))
    context.tableHandler.tableDataChanged()
    val newSelection = 0 until selection.size
    context.tableHandler setSelectedIndices newSelection.toArray
  }

  /**
    * @inheritdoc This implementation returns '''true''' if and only if there
    *             is a selection, and the first item is not selected.
    */
  override def isEnabled(context: PlaylistSelectionContext): Boolean =
    context.hasSelection && !context.isFirstElementSelected
}
