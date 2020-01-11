/*
 * Copyright 2015-2020 The Developers Team.
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
  * Test class for ''UnknownPropertyResolver'' and concrete
  * ''SongTitleProcessor'' implementations.
  */
class UnknownPropertyResolverSpec extends FlatSpec with Matchers {
  /**
    * Creates a test resolver instance. It is possible to specify a list of
    * song title processors. If defined, the processors are injected into the
    * trait. Otherwise, the default processors (i.e. none) are used.
    *
    * @param processors an optional list of title processors
    * @return the test instance
    */
  private def createResolver(processors: Option[List[SongTitleProcessor]] = None):
  UnknownPropertyResolver =
    new UnknownPropertyResolver {
      override def resolveAlbumName(songID: MediaFileID): String = "Album " + songID

      override def resolveArtistName(songID: MediaFileID): String = "Artist " + songID

      override def titleProcessors: List[SongTitleProcessor] =
        processors getOrElse super.titleProcessors
    }

  /**
    * Creates a song ID from the specified URI.
    *
    * @param uri the URI
    * @return the song ID
    */
  private def createSongID(uri: String): MediaFileID =
    MediaFileID(MediumID("someMedium", None), uri)

  "An UnknownPropertyResolver" should "return the URI as title" in {
    val uri = "C:\\music\\song.mp3"
    val resolver = createResolver()

    resolver.resolveTitle(createSongID(uri)) should be(uri)
  }

  it should "apply all title processors" in {
    def processor(index: Int): SongTitleProcessor =
      new SongTitleProcessor {
        override def processTitle(title: String): String =
          title + "," + index
      }

    val processors = List(processor(1), processor(2), processor(3))
    val uri = "uri"
    val resolver = createResolver(processors = Some(processors))

    resolver.resolveTitle(createSongID(uri)) should be(uri + ",1,2,3")
  }

  "SongTitlePathProcessor" should "extract the title from a very simple URI" in {
    val uri = "OnlyAName"

    SongTitlePathProcessor.processTitle(uri) should be(uri)
  }

  it should "extract the title from an URI with prefix" in {
    val uri = "song://TestSong"

    SongTitlePathProcessor.processTitle(uri) should be("TestSong")
  }

  it should "handle an empty URI when extracting the title" in {
    SongTitlePathProcessor.processTitle("") should be("")
  }

  it should "support backslash as path separator" in {
    val uri = "C:\\Temp\\test\\song.mp3"

    SongTitlePathProcessor.processTitle(uri) should be("song.mp3")
  }

  "SongTitleExtensionProcessor" should "handle a title without extension" in {
    val title = "Title without extension"

    SongTitleExtensionProcessor.processTitle(title) should be(title)
  }

  it should "remove an existing file extension" in {
    val ext = ".mp3"
    val title = "1. Song"

    SongTitleExtensionProcessor.processTitle(title + ext) should be(title)
  }

  "SongTitleDecodeProcessor" should "URL-decode the title" in {
    val title = "My%20test%20song%20%28nice%29%2A%2b%2C%2d%2E%2F.mp3"
    val expTitle = "My test song (nice)*+,-./.mp3"

    SongTitleDecodeProcessor.processTitle(title) should be(expTitle)
  }

  it should "only apply URL encoding if necessary" in {
    val uris = List("Song + Test = 80 %", "%xy", "% 100", "%20Test%20%%30", "%1")

    uris foreach { uri =>
      SongTitleDecodeProcessor.processTitle(uri) should be(uri)
    }
  }

  "SongTitleRemoveTrackProcessor" should "remove a leading track number" in {
    val title = "The title"
    val prefixes = List("01 ", "2-", "03 - ", "04   -   ", "5.", "06. ")
    val processor = new SongTitleRemoveTrackProcessor(100)

    prefixes foreach { p =>
      processor.processTitle(p + title) should be(title)
    }
  }

  it should "not modify other titles" in {
    val titles = List("A title", "CD1 - 02 - song", "My 100 favorites", "3Steps", "88")
    val processor = new SongTitleRemoveTrackProcessor(100)

    titles foreach { t =>
      processor.processTitle(t) should be(t)
    }
  }

  it should "take the maximum track number into account" in {
    val title = "99 Air Balloons"
    val processor = new SongTitleRemoveTrackProcessor(98)

    processor.processTitle(title) should be(title)
  }

  it should "handle number conversion errors" in {
    val title = "999999999999999999 Air Balloons"
    val processor = new SongTitleRemoveTrackProcessor(98)

    processor.processTitle(title) should be(title)
  }
}
