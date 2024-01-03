/*
 * Copyright 2015-2024 The Developers Team.
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
  * A trait defining the creation of [[SongData]] instances.
  *
  * This trait abstracts the logic how default values for missing meta data
  * properties are obtained. Concrete implementations have to implement a
  * corresponding strategy.
  */
trait SongDataFactory:
  /**
    * Creates a new ''SongData'' instance based on the parameters provided.
    *
    * @param id       the unique ID of the song in the media archive
    * @param metaData the meta data available for this song
    * @return the newly created ''SongData'' instance
    */
  def createSongData(id: MediaFileID, metaData: MediaMetaData): SongData
