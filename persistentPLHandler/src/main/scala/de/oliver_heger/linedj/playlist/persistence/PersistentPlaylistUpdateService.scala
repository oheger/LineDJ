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

package de.oliver_heger.linedj.playlist.persistence

import de.oliver_heger.linedj.platform.audio.playlist.service.PlaylistService
import de.oliver_heger.linedj.platform.audio.{AudioPlayerStateChangeRegistration, AudioPlayerStateChangedEvent, SetPlaylist}
import de.oliver_heger.linedj.platform.bus.{ComponentID, ConsumerSupport}
import de.oliver_heger.linedj.platform.mediaifc.ext.AvailableMediaExtension.AvailableMediaRegistration
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediaFileID, MediumID}
import scalaz.State
import scalaz.State._

/**
  * A class representing the state managed by the persistent playlist handler.
  *
  * @param componentID        stores the ID of the playlist handler component
  * @param loadedPlaylist     stores the playlist that has been loaded from
  *                           persistent storage
  * @param referencedMediaIDs a set with medium IDs referenced by the current
  *                           playlist
  * @param availableMediaIDs  a set with the IDs of media that are currently
  *                           available in the archive
  * @param availableChecksums a set with checksum strings for the media that
  *                           are currently available in the archive
  * @param messages           messages to be published on the bus
  */
case class PersistentPlaylistState(componentID: Option[ComponentID],
                                   loadedPlaylist: Option[SetPlaylist],
                                   referencedMediaIDs: Option[Set[MediaFileID]],
                                   availableMediaIDs: Set[MediumID],
                                   availableChecksums: Set[String],
                                   messages: List[Any])

/**
  * Interface of a service that updates the persistent playlist state when
  * specific events occur.
  *
  * With this service the playlist loaded from persistent storage can be
  * tracked. As soon as all referenced media are available, corresponding
  * messages to update the playlist are generated.
  */
trait PersistentPlaylistStateUpdateService:
  /**
    * Type alias for updates of the persistent playlist state.
    */
  type StateUpdate[A] = State[PersistentPlaylistState, A]

  /**
    * Updates the state when the playlist handler component is activated.
    *
    * @param compID   the component ID
    * @param callback the callback to receive available media notifications
    * @return the updated state
    */
  def activate(compID: ComponentID,
               callback: ConsumerSupport.ConsumerFunction[AvailableMedia]): StateUpdate[Unit]

  /**
    * Updates the state when the persistent playlist has been loaded.
    *
    * @param playlist the playlist
    * @param callback the callback to receive audio player change events
    * @return the updated state
    */
  def playlistLoaded(playlist: SetPlaylist,
                     callback: ConsumerSupport.ConsumerFunction[AudioPlayerStateChangedEvent]):
  StateUpdate[Unit]

  /**
    * Updates the state when a notification about new available media arrives.
    * This may cause an activation of the current playlist if it is present and
    * if now all referenced media are available.
    *
    * @param av the new available media
    * @return the updated state
    */
  def availableMediaArrived(av: AvailableMedia): StateUpdate[Unit]

  /**
    * Updates the state when messages are published on the message bus. The
    * messages to be sent in the current state are returned; the message
    * collection in the updated state is reset.
    *
    * @return the updated state and the messages to be sent
    */
  def fetchMessages(): StateUpdate[Iterable[Any]]

  /**
    * Handles the activation of the playlist handler component. Updates the
    * state accordingly and returns the messages to be sent.
    *
    * @param compID   the component ID
    * @param callback the callback to receive available media notifications
    * @return the updated state and the messages to be sent
    */
  def handleActivation(compID: ComponentID,
                       callback: ConsumerSupport.ConsumerFunction[AvailableMedia]):
  StateUpdate[Iterable[Any]] = for
    _ <- activate(compID, callback)
    msg <- fetchMessages()
  yield msg

  /**
    * Handles the life-cycle event that the playlist has been loaded. Updates
    * the state accordingly and returns the messages to be sent.
    *
    * @param playlist the playlist
    * @param callback the callback to receive audio player change events
    * @return the updated state and the messages to be sent
    */
  def handlePlaylistLoaded(playlist: SetPlaylist,
                           callback: ConsumerSupport
                           .ConsumerFunction[AudioPlayerStateChangedEvent]):
  StateUpdate[Iterable[Any]] = for
    _ <- playlistLoaded(playlist, callback)
    msg <- fetchMessages()
  yield msg

  /**
    * Handles a notification about available media. Updates the state
    * accordingly and returns the messages to be sent.
    *
    * @param av the new available media
    * @return the updated state and the messages to be sent
    */
  def handleNewAvailableMedia(av: AvailableMedia): StateUpdate[Iterable[Any]] = for
    _ <- availableMediaArrived(av)
    msg <- fetchMessages()
  yield msg

object PersistentPlaylistStateUpdateServiceImpl extends PersistentPlaylistStateUpdateService {
  /**
    * Constant for the initial persistent playlist state. This represents the
    * state before the playlist handler has been activated.
    */
  val InitialState: PersistentPlaylistState =
    PersistentPlaylistState(componentID = None, loadedPlaylist = None,
      referencedMediaIDs = None, availableMediaIDs = Set.empty, availableChecksums = Set.empty,
      messages = Nil)

  override def activate(compID: ComponentID, callback: ConsumerSupport
  .ConsumerFunction[AvailableMedia]): PersistentPlaylistStateUpdateServiceImpl
  .StateUpdate[Unit] = modify { s =>
    s.componentID match
      case Some(_) => s
      case None =>
        s.copy(componentID = Some(compID),
          messages = List(AvailableMediaRegistration(compID, callback)))
  }

  /**
    * Updates the state when the persistent playlist has been loaded.
    *
    * @param playlist the playlist
    * @param callback the callback to receive audio player change events
    * @return the updated state
    */
  override def playlistLoaded(playlist: SetPlaylist, callback: ConsumerSupport
  .ConsumerFunction[AudioPlayerStateChangedEvent]): PersistentPlaylistStateUpdateServiceImpl
  .StateUpdate[Unit] = modify { s =>
    s.componentID map { cid =>
      val messages = AudioPlayerStateChangeRegistration(cid, callback) :: s.messages
      val referencedMediaIDs = extractReferencedMedia(playlist)
      val playlistActive = canActivatePlaylist(referencedMediaIDs, s.availableMediaIDs,
        s.availableChecksums)
      val finalMessages = if playlistActive then playlist :: messages else messages
      s.copy(loadedPlaylist = Some(playlist),
        referencedMediaIDs = if playlistActive then None else Some(referencedMediaIDs),
        messages = finalMessages)
    } getOrElse s
  }

  override def availableMediaArrived(av: AvailableMedia): StateUpdate[Unit] = modify { s =>
    s.loadedPlaylist match
      case Some(pl) =>
        val playlistActive = s.referencedMediaIDs.exists(m =>
          canActivatePlaylist(m, av.mediaIDs, extractChecksumSet(av)))
        if playlistActive then
          s.copy(messages = pl :: s.messages,
            availableMediaIDs = Set.empty, referencedMediaIDs = None)
        else s.copy(availableMediaIDs = Set.empty)

      case None =>
        s.copy(availableMediaIDs = av.mediaIDs,
          availableChecksums = extractChecksumSet(av))
  }

  override def fetchMessages(): StateUpdate[Iterable[Any]] = State { s =>
    (s.copy(messages = Nil), s.messages.reverse)
  }

  /**
    * Checks whether all media referenced by the playlist are actually
    * available. For each referenced medium either the medium ID or its
    * checksum must be available.
    *
    * @param referencedMediaIDs the collection of referenced media
    * @param availableMediaIDs  the set with available media IDs
    * @param availableChecksums the set with available checksum strings
    * @return a flag whether all referenced media are available
    */
  private def canActivatePlaylist(referencedMediaIDs: Iterable[MediaFileID],
                                  availableMediaIDs: Set[MediumID],
                                  availableChecksums: Set[String]): Boolean =
    referencedMediaIDs forall { fid =>
      availableMediaIDs.contains(fid.mediumID) ||
        fid.checksum.exists(availableChecksums.contains)
    }

  /**
    * Extracts a set with ''MediaFileID'' objects representing the media that
    * are referenced by the current playlist. For this purpose, only the medium
    * ID and the checksum are relevant.
    *
    * @param playlist the playlist
    * @return a set with referenced media IDs
    */
  private def extractReferencedMedia(playlist: SetPlaylist): Set[MediaFileID] =
    PlaylistService.toSongList(playlist.playlist).map(_.copy(uri = null)).toSet

  /**
    * Extracts a set with checksum strings from the available media object.
    *
    * @param av the ''AvailableMedia''
    * @return the resulting set
    */
  private def extractChecksumSet(av: AvailableMedia): Set[String] =
    av.mediumInfos.map(_.checksum).toSet
}