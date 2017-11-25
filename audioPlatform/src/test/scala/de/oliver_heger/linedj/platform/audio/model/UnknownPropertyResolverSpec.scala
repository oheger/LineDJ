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

package de.oliver_heger.linedj.platform.audio.model

import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''UnknownPropertyResolver''.
  */
class UnknownPropertyResolverSpec extends FlatSpec with Matchers {
  /**
    * Creates a test resolver instance.
    *
    * @return the test instance
    */
  private def createResolver(): UnknownPropertyResolver =
    new UnknownPropertyResolver {
      override def resolveAlbumName(songID: MediaFileID): String = "Album " + songID

      override def resolveArtistName(songID: MediaFileID): String = "Artist " + songID
    }

  /**
    * Creates a song ID from the specified URI.
    *
    * @param uri the URI
    * @return the song ID
    */
  private def createSongID(uri: String): MediaFileID =
    MediaFileID(MediumID("someMedium", None), uri)

  "An UnknownPropertyResolver" should "return the last part of the URI as title" in {
    val uri = "C:\\music\\song.mp3"
    val resolver = createResolver()

    resolver.resolveTitle(createSongID(uri)) should be("song")
  }

  it should "extract the title from a very simple URI" in {
    val uri = "OnlyAName"
    val resolver = createResolver()

    resolver.resolveTitle(createSongID(uri)) should be(uri)
  }

  it should "extract the title from an URI with prefix" in {
    val uri = "song://TestSong.mp3"
    val resolver = createResolver()

    resolver.resolveTitle(createSongID(uri)) should be("TestSong")
  }

  it should "handle an empty URI when extracting the title" in {
    val resolver = createResolver()

    resolver.resolveTitle(createSongID("")) should be("")
  }
}
