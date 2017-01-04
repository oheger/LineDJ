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

package de.oliver_heger.linedj.pleditor.ui.playlist

/**
  * A task implementation which allows moves selected playlist items to the
  * top of the playlist.
  *
  * @param controller the ''PlaylistController''
  */
class PlaylistMoveTopTask(controller: PlaylistController) extends
AbstractPlaylistMoveBorderTask(controller) with PlaylistManipulator {

  /**
    * @inheritdoc This implementation returns '''true''' if and only if there
    *             is a selection, and the first item is not selected.
    */
  override def isEnabled(context: PlaylistSelectionContext): Boolean =
    context.hasSelection && !context.isFirstElementSelected

  /**
    * @inheritdoc This implementation adds the selected part first followed by
    *             the unselected part.
    */
  override protected def combinePartitions(context: PlaylistSelectionContext, partition: (List[
    (AnyRef, Int)], List[(AnyRef, Int)])): Unit = {
    appendToModel(context, partition._1)
    appendToModel(context, partition._2)
  }

  /**
    * @inheritdoc This implementation returns a selection with comprises all
    *             elements moved to the top.
    */
  override protected def nextSelection(context: PlaylistSelectionContext, oldSelection: Set[Int])
  : Array[Int] = (0 until oldSelection.size).toArray
}
