/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.browser.model

import de.oliver_heger.linedj.metadata.MediaMetaData
import net.sf.jguiraffe.gui.app.ApplicationContext

object SongDataFactory {
  /** Resource key for the unknown album name. */
  val KeyUnknownAlbum = "unknownAlbum"

  /** Resource key for the unknown artist name. */
  val KeyUnknownArtist = "unknownArtist"
}

/**
 * A class for creating [[SongData]] objects.
 *
 * The creation of a ''SongData'' instance is a bit more complex because an
 * [[UnknownNameResolver]] has to be provided. This class handles this case.
 * It is initialized with an ''ApplicationContext'' which can be used to
 * obtained localized names for unknown artists or albums.
 *
 * @param appCtx the application context
 */
class SongDataFactory(appCtx: ApplicationContext) extends UnknownNameResolver {

  import SongDataFactory._

  /**
   * The name of an unknown album. This is obtained from resources.
   */
  override lazy val unknownAlbumName: String = appCtx getResourceText KeyUnknownAlbum

  /**
   * The name of an unknown artist. This is obtained from resources.
   */
  override lazy val unknownArtistName: String = appCtx getResourceText KeyUnknownArtist

  /**
   * Creates a new ''SongData'' instance for the specified data.
   * @param uri the URI of the song
   * @param metaData song meta data
   * @return the ''SongData''
   */
  def createSongData(uri: String, metaData: MediaMetaData): SongData =
    SongData(uri, metaData, this)
}
