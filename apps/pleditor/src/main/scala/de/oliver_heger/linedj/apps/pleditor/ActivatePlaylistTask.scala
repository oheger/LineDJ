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

package de.oliver_heger.linedj.apps.pleditor

import de.oliver_heger.linedj.platform.audio2.AudioPlayerCommands
import de.oliver_heger.linedj.platform.audio2.playlist.{Playlist, PlaylistService}
import de.oliver_heger.linedj.platform.comm.MessageBus

/**
  * Task implementation for the _activate playlist_ action.
  *
  * This action generates a playlist from the current songs in the table model
  * and passes it the audio platform via the message bus.
  *
  * @param playlistService the playlist service
  * @param messageBus      the message bus
  * @param tableModel      the table model collection
  */
class ActivatePlaylistTask(playlistService: PlaylistService[Playlist, String],
                           messageBus: MessageBus,
                           tableModel: java.util.List[SongData]) extends Runnable:
  override def run(): Unit =
    import scala.jdk.CollectionConverters.*
    val playlist = playlistService.toPlaylist(tableModel.asScala.map(_.fileID).toList, 0)
    playlist foreach (messageBus publish AudioPlayerCommands.SetPlaylist(_))
