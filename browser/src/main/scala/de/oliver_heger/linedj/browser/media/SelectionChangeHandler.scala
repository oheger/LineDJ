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

package de.oliver_heger.linedj.browser.media

import de.oliver_heger.linedj.shared.archive.media.MediumID
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, TableHandler, TreeHandler}
import net.sf.jguiraffe.gui.builder.event.{FormChangeEvent, FormChangeListener}

/**
 * An event listener class which reacts on selection change events on multiple
 * controls and notifies the [[MediaController]].
 *
 * When the user selects another medium in the combo box the media controller
 * is notified so that the data of the new medium can be requested.
 *
 * When another artist or album is selected in the tree view, a notification is
 * sent as well; then the table view has to be updated.
 *
 * @param controller the ''MediaController''
 */
class SelectionChangeHandler(controller: MediaController) extends FormChangeListener:
  override def elementChanged(formChangeEvent: FormChangeEvent): Unit =
    formChangeEvent.getHandler match
      case listHandler: ListComponentHandler =>
        controller selectMedium listHandler.getData.asInstanceOf[MediumID]

      case treeHandler: TreeHandler =>
        controller selectAlbums treeHandler.getSelectedPaths

      case _: TableHandler =>
        controller.songSelectionChanged()
