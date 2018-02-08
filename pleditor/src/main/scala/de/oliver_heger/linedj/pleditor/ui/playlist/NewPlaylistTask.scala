/*
 * Copyright 2015-2018 The Developers Team.
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

import de.oliver_heger.linedj.platform.audio.SetPlaylist
import de.oliver_heger.linedj.platform.audio.playlist.Playlist
import de.oliver_heger.linedj.platform.comm.MessageBus

/**
  * The task implementation for the ''new playlist'' action.
  *
  * This task resets the current playlist of the platform audio player by
  * setting an empty one that is not closed. It also removes all songs from the
  * current table model.
  *
  * @param controller the ''PlaylistController''
  * @param messageBus the message bus
  */
class NewPlaylistTask(controller: PlaylistController, messageBus: MessageBus)
  extends AbstractPlaylistManipulationTask(controller) with PlaylistManipulator {
  /**
    * @inheritdoc This implementation clears the table model and send a
    *             ''SetPlaylist'' message with an empty, open playlist.
    */
  override def updatePlaylist(context: PlaylistSelectionContext): Unit = {
    context.tableHandler.getModel.clear()
    context.tableHandler.tableDataChanged()
    messageBus publish SetPlaylist(playlist = Playlist(Nil, Nil), closePlaylist = false)
  }

  /**
    * @inheritdoc This implementation returns always '''true'''.
    */
  override def isEnabled(context: PlaylistSelectionContext): Boolean = true
}
