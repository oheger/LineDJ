/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.player.ui

import de.oliver_heger.linedj.platform.audio.AudioPlayerState
import de.oliver_heger.linedj.platform.audio.model.SongDataFactory
import de.oliver_heger.linedj.platform.audio.playlist._
import de.oliver_heger.linedj.shared.archive.media.MediaFileID
import net.sf.jguiraffe.gui.builder.components.model.TableHandler

/**
  * A class responsible for updating the table control that displays the
  * playlist of the audio player.
  *
  * This class is responsible for updating the table control with the playlist
  * items based on events received from the audio platform. It interacts with
  * a [[PlaylistMetaDataService]] to convert playlist update notifications to
  * actions on the table model. These actions are then executed, so that the
  * table reflects the current status of the playlist. Also, the selection of
  * the table is set to the current song in the playlist, which is obtained
  * from the [[PlaylistService]].
  *
  * @param songDataFactory the factory for ''SongData'' objects
  * @param plMetaService   the ''PlaylistMetaDataService''
  * @param plService       the ''PlaylistService''
  * @param tableHandler    the handler for the table control
  */
class PlaylistTableController(songDataFactory: SongDataFactory,
                              plMetaService: PlaylistMetaDataService,
                              plService: PlaylistService[Playlist, MediaFileID],
                              tableHandler: TableHandler) {
  /** Stores the most recent state of the meta data resolver. */
  private var metaDataState = plMetaService.InitialState

  /**
    * Notifies this object about a change in the state of the audio player.
    * If necessary, the playlist table is updated to reflect the new state.
    *
    * @param state the new state of the audio player
    */
  def handlePlayerStateUpdate(state: AudioPlayerState): Unit = {
    val (delta, nextState) = plMetaService.processPlaylistUpdate(state.playlist,
      state.playlistSeqNo, metaDataState)(songDataFactory)
    processDelta(delta, nextState)
    updateTableSelection(state)
  }

  /**
    * Notifies this object that new meta data has been fetched. Updates the
    * table accordingly.
    *
    * @param metaData the new meta data
    */
  def handleMetaDataUpdate(metaData: PlaylistMetaData): Unit = {
    val selIdx = tableHandler.getSelectedIndex
    val (delta, nextState) = plMetaService.processMetaDataUpdate(metaData,
      metaDataState)(songDataFactory)
    processDelta(delta, nextState)
    tableHandler setSelectedIndex selIdx
  }

  /**
    * Processes a delta object obtained from the playlist meta data service.
    * Applies the specified changes on the table model and sends update
    * notifications to the table handler.
    *
    * @param delta     the delta to be processed
    * @param nextState the next state of meta data resolving
    */
  private def processDelta(delta: MetaDataResolveDelta, nextState: MetaDataResolveState): Unit = {
    if (delta.fullUpdate) processFullPlaylistUpdate(delta)
    else processPartialPlaylistUpdate(delta)
    metaDataState = nextState
  }

  /**
    * Processes a delta object from the playlist meta data service that
    * indicates a full update of the playlist information.
    *
    * @param delta the delta to be processed
    */
  private def processFullPlaylistUpdate(delta: MetaDataResolveDelta): Unit = {
    lazy val model = tableHandler.getModel
    tableHandler.getModel.clear()
    delta.resolvedSongs foreach (e => model.add(e._1))
    tableHandler.tableDataChanged()
  }

  /**
    * Processes a delta object from the playlist meta data service that
    * requires only partial updates on the playlist.
    *
    * @param delta the delta to be processed
    */
  private def processPartialPlaylistUpdate(delta: MetaDataResolveDelta): Unit = {
    lazy val model = tableHandler.getModel
    delta.resolvedSongs foreach (e => model.set(e._2, e._1))
    delta.updatedRanges foreach (t => tableHandler.rowsUpdated(t._1, t._2))
  }

  /**
    * Updates the selection of the playlist table after an update of the
    * playlist has been received. This method makes sure that the current song
    * in the playlist - if any - is selected in the table.
    *
    * @param state the updated state of the audio player
    */
  private def updateTableSelection(state: AudioPlayerState): Unit = {
    plService currentIndex state.playlist match {
      case Some(idx) =>
        tableHandler setSelectedIndex idx
      case None =>
        tableHandler.clearSelection()
    }
  }
}
