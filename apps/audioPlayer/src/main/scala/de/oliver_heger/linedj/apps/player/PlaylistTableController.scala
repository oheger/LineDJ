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

package de.oliver_heger.linedj.apps.player

import net.sf.jguiraffe.gui.builder.components.model.TableHandler

/**
  * A class responsible for managing the table control that displays the
  * playlist of the audio player.
  *
  * This class wraps the [[TableHandler]] for the table control that displays
  * the current playlist of the audio player. When there is a change in the
  * UI state of the application, it is passed the updated 
  * [[AudioPlayerUIState]] object. Based on this object, it updates the
  * table control accordingly.
  *
  * @param tableHandler the handler for the table control
  */
class PlaylistTableController(tableHandler: TableHandler):
  /** Stores the UI state currently reflected by the table. */
  private var currentState = AudioPlayerUIState.Initial

  /**
    * Notifies this object about a change in the state of the audio player.
    * If necessary, the playlist table is updated to reflect the new state.
    *
    * @param state the new state of the audio player
    */
  def handleStateUpdate(state: AudioPlayerUIState): Unit =
    if currentState.playlist != state.playlist then
      populateTableModel(state.playlist)
      updateTableSelection(state.optCurrentIndex)
    else if currentState.optCurrentIndex != state.optCurrentIndex then
      updateTableSelection(state.optCurrentIndex)

    currentState = state

  /**
    * Updates the selection of the playlist table after an update of the UI
    * state has been received. This method makes sure that the current song
    * in the playlist - if any - is selected in the table.
    *
    * @param optIndex the optional index of the current song
    */
  private def updateTableSelection(optIndex: Option[Int]): Unit =
    optIndex match
      case Some(idx) =>
        tableHandler.setSelectedIndex(idx)
      case None =>
        tableHandler.clearSelection()

  /**
    * Fills the table model with the data of a playlist.
    *
    * @param playlist the playlist
    */
  private def populateTableModel(playlist: List[SongData]): Unit =
    import scala.jdk.CollectionConverters.*
    val model = tableHandler.getModel
    model.clear()
    model.addAll(playlist.asJava)
    tableHandler.tableDataChanged()
