/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.platform.model

import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object SongDataSpec {
  /** A test medium ID. */
  private val Medium = MediumID("someURI", Some("somePath"))
}

/**
 * Test class for ''SongData''.
 */
class SongDataSpec extends FlatSpec with Matchers with MockitoSugar {
  import SongDataSpec._

  it should "return the title from the meta data if available" in {
    val title = "Bohemian Rhapsody"
    val s = SongData(Medium, "s0", MediaMetaData(title = Some(title)), null)
    s.getTitle should be(title)
  }

  it should "return the last part of the URI as title as fallback" in {
    val uri = "C:\\music\\song.mp3"
    val s = SongData(Medium, uri, MediaMetaData(), null)
    s.getTitle should be("song")
  }

  it should "extract the title from a very simple URI" in {
    val uri = "OnlyAName"
    val s = SongData(Medium, uri, MediaMetaData(), null)
    s.getTitle should be(uri)
  }

  it should "handle URIs with prefix when extracting song titles" in {
    val uri = "song://TestSong.mp3"
    val s = SongData(Medium, uri, MediaMetaData(), null)
    s.getTitle should be("TestSong")
  }

  it should "return the name of the album from meta data" in {
    val Album = "Brothers in Arms"
    val data = SongData(Medium, "uri", MediaMetaData(album = Some(Album)), null)

    data.getAlbum should be(Album)
  }

  it should "query the resolver for an unknown album name" in {
    val Album = "Undefined album"
    val resolver = mock[UnknownNameResolver]
    when(resolver.unknownAlbumName).thenReturn(Album)
    val data = SongData(Medium, "uri", MediaMetaData(), resolver)

    data.getAlbum should be(Album)
    data.getAlbum should be(Album)
    verify(resolver, times(1)).unknownAlbumName
  }

  it should "return the name of the artist from meta data" in {
    val Artist = "Dire Straits"
    val data = SongData(Medium, "uri", MediaMetaData(artist = Some(Artist)), null)

    data.getArtist should be(Artist)
  }

  it should "query the resolver for an unknown artist name" in {
    val Artist = "Unknown Artist"
    val resolver = mock[UnknownNameResolver]
    when(resolver.unknownArtistName).thenReturn(Artist)
    val data = SongData(Medium, "uri", MediaMetaData(), resolver)

    data.getArtist should be(Artist)
    data.getArtist should be(Artist)
    verify(resolver, times(1)).unknownArtistName
  }

  it should "return the duration from meta data" in {
    val Duration = 20150822
    val data = SongData(Medium, "uri", MediaMetaData(duration = Some(Duration)), null)

    data.getDuration should be(Duration)
  }

  it should "return a negative duration if not defined in meta data" in {
    val data = SongData(Medium, "uri", MediaMetaData(), null)

    data.getDuration should be(-1)
  }

  it should "return the track number for meta data" in {
    val Track = 8
    val data = SongData(Medium, "uri", MediaMetaData(trackNumber = Some(Track)), null)

    data.getTrackNumber should be(Track.toString)
  }

  it should "return an empty string as track number if undefined" in {
    val data = SongData(Medium, "uri", MediaMetaData(), null)

    data.getTrackNumber shouldBe 'empty
  }
}
