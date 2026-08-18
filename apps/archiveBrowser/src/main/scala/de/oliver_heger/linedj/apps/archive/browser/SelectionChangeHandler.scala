/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.apps.archive.browser

import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, TreeHandler}
import net.sf.jguiraffe.gui.builder.event.{FormChangeEvent, FormChangeListener}

/**
  * An event handler implementation that is registered at multiple UI
  * components. When it receives a selection change event, it propagates this
  * notification to the controller. For this purpose, it has to determine the
  * source of the change event and its semantic meaning.
  *
  * @param controller the controller
  */
class SelectionChangeHandler(controller: Controller) extends FormChangeListener:
  override def elementChanged(e: FormChangeEvent): Unit =
    e.getHandler match
      case list: ListComponentHandler =>
        val mediumSelection = Option(list.getData).map(_.asInstanceOf[Checksums.MediumChecksum])
        controller.mediumSelected(mediumSelection)

      case tree: TreeHandler =>
        val selection = Option(tree.getSelectedPath)
          .map(_.getTargetNode.getValue)
          .filter(_.isInstanceOf[Controller.SongOwnerID])
          .map(_.asInstanceOf[Controller.SongOwnerID])
        controller.artistAlbumSelected(selection)
