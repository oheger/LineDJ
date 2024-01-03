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

import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object DefaultSongDataFactorySpec:
  /** Test song ID. */
  private val SongID = MediaFileID(MediumID("medium", None), "songUri")

  /** Title of a test song. */
  private val Title = "Your Latest Trick"

  /** Artist of the test song. */
  private val Artist = "Dire Straits"

  /** Album of the test song. */
  private val Album = "Brothers in Arms"

  /** Suffix returned by the test resolver when extracting a title. */
  private val ResolvedTitle = "_title"

  /** Suffix returned by the test resolver when extracting an artist. */
  private val ResolvedArtist = "_artist"

  /** Suffix returned by the test resolver when extracting an album. */
  private val ResolvedAlbum = "_album"

  /** A meta data object with all relevant key properties defined. */
  private val SongMetaData = MediaMetaData(title = Some(Title), artist = Some(Artist),
    album = Some(Album))

  /**
    * A test resolver object. This object produces property names derived from
    * the passed in song ID and key property to be resolved. This allows a
    * verification whether the correct method was invoked.
    */
  private val Resolver = new UnknownPropertyResolver:
    override def resolveAlbumName(songID: MediaFileID): String =
      songID.toString + ResolvedAlbum

    override def resolveArtistName(songID: MediaFileID): String =
      songID.toString + ResolvedArtist

    override def resolveTitle(songID: MediaFileID): String =
      songID.toString + ResolvedTitle

  /**
    * Returns the string produced by the test resolver for the specified
    * property.
    *
    * @param suffix the suffix for the expected property
    * @return the full property name returned by the test resolver
    */
  private def resolvedProperty(suffix: String): String =
    SongID.toString + suffix

  /**
    * Creates a song using a test factory instance. Depending on the passed in
    * flag either complete meta data is provided or none.
    *
    * @param withMetaData flag whether meta data should be defined
    * @return the song created by the test factory
    */
  private def createSong(withMetaData: Boolean): SongData =
    val metaData = if withMetaData then SongMetaData else MediaMetaData()
    val factory = new DefaultSongDataFactory(Resolver)
    factory.createSongData(SongID, metaData)

/**
  * Test class for ''DefaultSongDataFactory''.
  */
class DefaultSongDataFactorySpec extends AnyFlatSpec with Matchers:

  import DefaultSongDataFactorySpec._

  "A DefaultSongDataFactory" should "initialize basic properties" in:
    val song = createSong(withMetaData = true)

    song.id should be theSameInstanceAs SongID
    song.metaData should be theSameInstanceAs SongMetaData

  it should "use the title from meta data if defined" in:
    val song = createSong(withMetaData = true)

    song.title should be(Title)

  it should "use the artist from meta data if defined" in:
    val song = createSong(withMetaData = true)

    song.artist should be(Artist)

  it should "use the album from meta data if defined" in:
    val song = createSong(withMetaData = true)

    song.album should be(Album)

  it should "call the resolver if the title is undefined" in:
    val song = createSong(withMetaData = false)

    song.title should be(resolvedProperty(ResolvedTitle))

  it should "call the resolver if the artist is undefined" in:
    val song = createSong(withMetaData = false)

    song.artist should be(resolvedProperty(ResolvedArtist))

  it should "call the resolver if the album is undefined" in:
    val song = createSong(withMetaData = false)

    song.album should be(resolvedProperty(ResolvedAlbum))
