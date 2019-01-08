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

package de.oliver_heger.linedj.platform.audio.model

import de.oliver_heger.linedj.shared.archive.media.MediaFileID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData

/**
  * A default implementation of the [[SongDataFactory]] trait.
  *
  * This implementation creates [[SongData]] objects from the passed in IDs and
  * meta data. Missing key properties in meta data are obtained from the
  * provided [[UnknownPropertyResolver]].
  *
  * @param resolver the resolver for missing meta data properties
  */
class DefaultSongDataFactory(val resolver: UnknownPropertyResolver) extends SongDataFactory {
  override def createSongData(id: MediaFileID, metaData: MediaMetaData): SongData =
    SongData(id, metaData,
      title = metaData.title getOrElse resolver.resolveTitle(id),
      artist = metaData.artist getOrElse resolver.resolveArtistName(id),
      album = metaData.album getOrElse resolver.resolveAlbumName(id))
}
