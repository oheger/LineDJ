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
import net.sf.jguiraffe.gui.app.ApplicationContext

import scala.collection.JavaConverters

/**
  * A specialized implementation of an ''UnknownPropertyResolver'' that
  * fetches the names of unknown artists or albums from resource constants.
  *
  * The ''ApplicationContext'' to resolve resources and the resource IDs to
  * be used are passed to the constructor.
  *
  * @param appCtx           the ''ApplicationContext''
  * @param resUnknownArtist resource ID for the unknown artist name
  * @param resUnknownAlbum  resource ID for the unknown album name
  * @param titleProcessors  the processors for generating a song title
  */
class UnknownPropertyResourceResolver(val appCtx: ApplicationContext,
                                      val resUnknownArtist: String,
                                      val resUnknownAlbum: String,
                                      override val titleProcessors: List[SongTitleProcessor])
  extends UnknownPropertyResolver {
  /**
    * Creates a new instance with the specified properties. This constructor is
    * intended to be called from Java code. The processors are provided as a
    * Java collection rather than a List.
    *
    * @param appCtx           the ''ApplicationContext''
    * @param resUnknownArtist resource ID for the unknown artist name
    * @param resUnknownAlbum  resource ID for the unknown album name
    * @param titleProcessors  the processors for generating a song title
    * @return the new instance
    */
  def this(appCtx: ApplicationContext, resUnknownArtist: String, resUnknownAlbum: String,
           titleProcessors: java.util.Collection[SongTitleProcessor]) = {
    this(appCtx, resUnknownArtist, resUnknownAlbum,
      JavaConverters.collectionAsScalaIterable(titleProcessors).toList)
  }

  /** The resolved name for an unknown artist. */
  lazy val unknownArtistName: String = appCtx getResourceText resUnknownArtist

  /** The resolved name for an unknown album. */
  lazy val unknownAlbumName: String = appCtx getResourceText resUnknownAlbum

  override def resolveArtistName(songID: MediaFileID): String = unknownArtistName

  override def resolveAlbumName(songID: MediaFileID): String = unknownAlbumName
}
