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

package de.oliver_heger.linedj.platform.audio.playlist

import de.oliver_heger.linedj.platform.audio.model.SongDataFactory

/**
  * Interface for a service that allows keeping track on meta data resolve
  * operations on playlist instances.
  *
  * When a new playlist is set for the audio player meta data information
  * about the songs in the list has to be obtained first from the media archive
  * (unless it is already available in a local cache). This may take some time
  * and may be handled in multiple chunks for larger lists.
  *
  * UI clients have the problem to display the current state of the playlist
  * and to update their display when new meta data becomes available.
  * Depending on the way the playlist is presented to users, this is not
  * trivial. Often all songs to be played are displayed in a table-like UI
  * control. The handling of such controls is typically complex; it requires
  * the management of a table model and specific change notifications if items
  * in the model have been changed. The main goal of this service is to support
  * clients with this use case.
  *
  * The basic idea is that clients receive notifications about changes in the
  * state of the audio player (which includes an updated playlist) and about
  * new meta data. They can then invoke specific service methods to gain
  * information how they should react on an update, such as which parts of the
  * table model need to be modified. So it is not necessary for clients to map
  * the structure of a [[PlaylistMetaData]] object directly to their internal
  * table model, but can rely on the more convenient (and index-based) data
  * structures used by this service.
  *
  * Note that in order to produce its results, a [[SongDataFactory]] must be
  * available. The service methods therefore just returns functions that expect
  * such a factory as input and return the actual results.
  */
trait PlaylistMetaDataService {
  /**
    * Constant for a state instance representing an initial state. Clients
    * that have just started should use this state for their first interaction
    * with the service.
    */
  val InitialState: MetaDataResolveState

  /**
    * Processes a notification about a potential update of the playlist. Based
    * on the information passed to this function, it generates a
    * ''MetaDataResolveDelta'' object to be interpreted by the client in order
    * to keep its UI up-to-date. An updated resolve state is returned as well.
    *
    * @param playlist the current playlist
    * @param seqNo    the current playlist sequence number
    * @param state    the current resolve state
    * @return function for a tuple with a delta object and an updated state
    */
  def processPlaylistUpdate(playlist: Playlist, seqNo: Int, state: MetaDataResolveState):
  SongDataFactory => (MetaDataResolveDelta, MetaDataResolveState)

  /**
    * Processes a notification about new meta data that is now available. Like
    * ''processPlaylistUpdate()'', a corresponding delta object and an updated
    * state are returned.
    *
    * @param data  the updated meta data for the current playlist
    * @param state the current resolve state
    * @return function for a tuple with a delta object and an updated state
    */
  def processMetaDataUpdate(data: PlaylistMetaData, state: MetaDataResolveState):
  SongDataFactory => (MetaDataResolveDelta, MetaDataResolveState)
}
