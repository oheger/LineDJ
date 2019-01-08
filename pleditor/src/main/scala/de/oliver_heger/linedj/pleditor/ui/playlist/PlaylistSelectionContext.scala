/*
 * Copyright 2015-2019 The Developers Team.
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

import net.sf.jguiraffe.gui.builder.components.model.TableHandler

/**
  * A class which contains information about the current selection in the
  * playlist table.
  *
  * It is sometimes necessary to evaluate information related to the current
  * selection in the playlist table, for instance to enable or disable certain
  * actions. To avoid that the required information has to be calculated
  * multiple times, it is encapsulated in this class.
  *
  * @param tableHandler the ''TableHandler'' for the playlist table
  */
case class PlaylistSelectionContext(tableHandler: TableHandler) {
  /** The currently selected indices in the playlist table. */
  val selectedIndices = tableHandler.getSelectedIndices

  /** A flag whether there is a selection. */
  lazy val hasSelection = selectedIndices.nonEmpty

  /** The minimum index of the selection. */
  lazy val minimumSelectionIndex = selectedIndices.min

  /** The maximum index of the selection. */
  lazy val maximumSelectionIndex = selectedIndices.max

  /**
    * A flag whether the first element in the table is selected. This
    * information is needed for instance to determine whether the selection can
    * be moved upwards.
    */
  lazy val isFirstElementSelected = minimumSelectionIndex == 0

  /**
    * A flag whether the last element in the table is selected. This
    * information is needed for instance to determine whether the selection can
    * be moved down.
    */
  lazy val isLastElementSelected = maximumSelectionIndex == tableHandler.getModel.size() - 1
}
