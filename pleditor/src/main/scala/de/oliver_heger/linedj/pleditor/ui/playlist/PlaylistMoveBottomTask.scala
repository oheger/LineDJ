/*
 * Copyright 2015-2023 The Developers Team.
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
  * A task implementation that moves selected items to the bottom of the
  * playlist.
  *
  * @param controller the ''PlaylistController''
  */
class PlaylistMoveBottomTask(controller: PlaylistController) extends
AbstractPlaylistMoveBorderTask(controller) {

  /**
    * Returns a flag whether the manipulation represented by this object is
    * currently available. This is used to enable or disable the associated
    * action.
    * @param context the ''PlaylistSelectionContext''
    * @return a flag whether this manipulation is available
    */
  override def isEnabled(context: PlaylistSelectionContext): Boolean =
    context.hasSelection && !context.isLastElementSelected

  /**
    * Combines the partitions of the original model in the correct order. This
    * method is called by ''updatePlaylist()''. The first element of the tuple
    * is the list with selected elements; the second element is the list with
    * not selected elements.
    * @param context the ''PlaylistSelectionContext''
    * @param partition the partition of the model
    */
  override protected def combinePartitions(context: PlaylistSelectionContext, partition: (List[
    (AnyRef, Int)], List[(AnyRef, Int)])): Unit = {
    appendToModel(context, partition._2)
    appendToModel(context, partition._1)
  }

  /**
    * Determines the next selection. This method is called after the playlist
    * has been updated. The selection returned by this method is set for the
    * table handler.
    * @param context the ''PlaylistSelectionContext''
    * @param oldSelection the set with the old selection
    * @return the array with the new selection
    */
  override protected def nextSelection(context: PlaylistSelectionContext, oldSelection: Set[Int])
  : Array[Int] = {
    val modelSize = context.tableHandler.getModel.size()
    (modelSize - oldSelection.size until modelSize).toArray
  }
}
