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
  * An abstract base class for tasks that move playlist items to the borders
  * (top or bottom) of the playlist.
  *
  * The concrete move tasks share the major part of the functionality. This has
  * been refactored into this base class.
  *
  * @param controller the ''PlaylistController''
  */
abstract class AbstractPlaylistMoveBorderTask(controller: PlaylistController) extends
AbstractPlaylistManipulationTask(controller) with PlaylistManipulator {
  /**
    * @inheritdoc This implementation creates a partition of the table model
    *             consisting of selected and not selected elements. Concrete
    *             subclasses are passed the partitions and can decide in
    *             which order they have to be concatenated.
    */
  override def updatePlaylist(context: PlaylistSelectionContext): Unit = {
    import scala.jdk.CollectionConverters._
    val selection = context.selectedIndices.toSet
    val model = context.tableHandler.getModel
    val indexedModel = model.asScala.toList.zipWithIndex
    val partition = indexedModel.partition(e => selection contains e._2)

    model.clear()
    combinePartitions(context, partition)
    context.tableHandler.tableDataChanged()
    context.tableHandler setSelectedIndices nextSelection(context, selection)
  }

  /**
    * Appends the given list with items to the playlist table model.
    * @param context the ''PlaylistSelectionContext''
    * @param part the part of the playlist to be appended
    */
  protected def appendToModel(context: PlaylistSelectionContext, part: List[(AnyRef, Int)]): Unit
  = {
    val model = context.tableHandler.getModel
    part foreach (e => model.add(e._1))
  }

  /**
    * Combines the partitions of the original model in the correct order. This
    * method is called by ''updatePlaylist()''. The first element of the tuple
    * is the list with selected elements; the second element is the list with
    * not selected elements.
    * @param context the ''PlaylistSelectionContext''
    * @param partition the partition of the model
    */
  protected def combinePartitions(context: PlaylistSelectionContext, partition: (List[(AnyRef,
    Int)], List[(AnyRef, Int)])): Unit

  /**
    * Determines the next selection. This method is called after the playlist
    * has been updated. The selection returned by this method is set for the
    * table handler.
    * @param context the ''PlaylistSelectionContext''
    * @param oldSelection the set with the old selection
    * @return the array with the new selection
    */
  protected def nextSelection(context: PlaylistSelectionContext, oldSelection: Set[Int]): Array[Int]
}
