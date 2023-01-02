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

package de.oliver_heger.linedj.platform.audio.playlist

import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.{ConsumerFunction, ConsumerRegistration}
import de.oliver_heger.linedj.shared.archive.media.MediaFileID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData

/**
  * A class storing meta data for the files in a playlist.
  *
  * A playlist as processed by the audio player component consists only of
  * references to the songs to be played. In order to display such a list in
  * the UI, more information about the songs is needed, e.g. things like artist
  * and title.
  *
  * This class provides this additional information in form of a map. Instances
  * are created when the playlist is changed. Then meta data for the songs in
  * the playlist is fetched automatically in background, and updated instances
  * are made available.
  *
  * Note that an instance may not contain meta data for all songs in the
  * playlist. The process of fetching meta data from the archive may still be
  * in progress, so some songs may not have been resolved yet.
  *
  * @param data a map from a file ID to corresponding meta data
  */
case class PlaylistMetaData(data: Map[MediaFileID, MediaMetaData])

/**
  * A message class representing a consumer registration for the meta data of
  * the current playlist.
  *
  * By publishing a message of this type on the system message bus, a component
  * can register itself for updates when meta data about songs in the playlist
  * becomes available. Multiple update notifications may be received until all
  * songs in the playlist have been resolved.
  *
  * @param id       the ID of the consumer component
  * @param callback the consumer function
  */
case class PlaylistMetaDataRegistration(override val id: ComponentID,
                                        override val callback:
                                        ConsumerFunction[PlaylistMetaData])
  extends ConsumerRegistration[PlaylistMetaData] {
  override def unRegistration: AnyRef = PlaylistMetaDataUnregistration(id)
}

/**
  * A message class representing the removal of a registration for playlist
  * meta data.
  *
  * @param id the ID of the consumer component
  */
case class PlaylistMetaDataUnregistration(id: ComponentID)

/**
  * A class representing the current state of a resolve operation for the meta
  * data of a playlist.
  *
  * This class is used by the playlist meta data service to keep track on an
  * ongoing resolve operation. Clients of the service have to provide an
  * instance. However, they should not make any assumptions about the content
  * of an instance, as its interpretation is an implementation detail of the
  * service.
  *
  * @param seqNo           the sequence number of the current playlist
  * @param unresolvedSongs a list with information about songs for which no
  *                        meta data is available yet
  * @param metaData        a map with meta data which is currently known
  */
case class MetaDataResolveState(seqNo: Int, unresolvedSongs: List[(MediaFileID, Int)],
                                metaData: Map[MediaFileID, MediaMetaData])

/**
  * A class describing the changes caused by a step in a meta data resolve
  * operation.
  *
  * Whenever new meta data is obtained from the media archive, songs in the
  * playlist may change to the resolved state. This typically triggers some
  * actions of a client, e.g. the UI has to be updated accordingly. The
  * information stored in an instance is sufficient to define such actions. It
  * is based on the assumption that the playlist is displayed in a tabular
  * form. Therefore, updated songs are associated with their (zero-based)
  * indices in the playlist.
  *
  * For UI clients it is often relevant which areas of a list structure are
  * affected by an update. They can then invalidate the corresponding indices
  * in UI controls and thus handle updates efficiently. To support this, an
  * instance holds a sequence of ranges that need to be updated. These are
  * pairs of a from index and a to index (both are including).
  *
  * An operation may also cause a complete update of the UI, e.g. if the
  * playlist has changed completely, and a new resolving process starts. This
  * is indicated by another flag.
  *
  * @param resolvedSongs list of songs with updated meta data and their indices
  * @param updatedRanges list of ranges affected by this change
  * @param fullUpdate    flag whether this delta represents a full change
  */
case class MetaDataResolveDelta(resolvedSongs: Iterable[(SongData, Int)],
                                updatedRanges: Iterable[(Int, Int)],
                                fullUpdate: Boolean)
