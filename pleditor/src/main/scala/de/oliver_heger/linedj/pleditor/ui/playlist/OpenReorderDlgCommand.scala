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

import net.sf.jguiraffe.gui.app.{ApplicationBuilderData, OpenWindowCommand}
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import net.sf.jguiraffe.locators.Locator

object OpenReorderDlgCommand:
  /** The name of the property under which the songs to be reordered are stored. */
  val ReorderSongsPropertyKey = "reorderSongs"

  /**
    * The name of the property under which the start index of the songs to be
    * reordered is stored.
    */
  val StartIndexPropertyKey = "reorderStartIndex"

/**
  * A command class which opens the dialog for reordering parts of the
  * playlist.
  *
  * This is a specialized command for opening a dialog window which ensures
  * that information about the songs to be reordered is stored in the Jelly
  * context. It also implements the [[PlaylistManipulator]] trait to
  * determine when the command is enabled.
  *
  * @param scriptLocator the locator to the script to be interpreted
  * @param tabHandler    the handler for the playlist table
  */
class OpenReorderDlgCommand(scriptLocator: Locator, tabHandler: TableHandler) extends
  OpenWindowCommand(scriptLocator) with PlaylistManipulator:

  import OpenReorderDlgCommand._

  /**
    * @inheritdoc This is just a dummy. This command does not modify the
    *             playlist itself.
    */
  override def updatePlaylist(context: PlaylistSelectionContext): Unit = {}

  /**
    * @inheritdoc This implementation returns '''true''' if and only if a
    *             selection exists.
    */
  override def isEnabled(context: PlaylistSelectionContext): Boolean = context.hasSelection

  /**
    * @inheritdoc This implementation stores information about the currently
    *             selected songs in the builder data.
    */
  override protected[playlist] def prepareBuilderData(builderData: ApplicationBuilderData): Unit =
    import scala.jdk.CollectionConverters._
    val context = new PlaylistSelectionContext(tabHandler)
    val selection = tabHandler.getModel.subList(context.minimumSelectionIndex, context
      .maximumSelectionIndex + 1)
    builderData.addProperty(ReorderSongsPropertyKey, selection.asScala.toList)
    builderData.addProperty(StartIndexPropertyKey, context.minimumSelectionIndex)
