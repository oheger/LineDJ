/*
 * Copyright 2015-2017 The Developers Team.
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
  extends ConsumerRegistration[PlaylistMetaData]

/**
  * A message class representing the removal of a registration for playlist
  * meta data.
  *
  * @param id the ID of the consumer component
  */
case class PlaylistMetaDataUnregistration(id: ComponentID)
