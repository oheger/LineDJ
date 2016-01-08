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

package de.oliver_heger.linedj.client.model

import de.oliver_heger.linedj.media.MediumID
import de.oliver_heger.linedj.metadata.MediaMetaData
import net.sf.jguiraffe.gui.app.ApplicationContext
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object SongDataFactorySpec {
  /** Constant for a medium ID. */
  private val Medium = MediumID("someMedium", Some("somePath"))

  /** Constant for a song URI. */
  private val Uri = "song://music.mp3"
}

/**
 * Test class for ''SongDataFactory''.
 */
class SongDataFactorySpec extends FlatSpec with Matchers with MockitoSugar {
  import SongDataFactorySpec._

  "A SongDataFactory" should "create correct SongData instances" in {
    val context = mock[ApplicationContext]
    val factory = new SongDataFactory(context)
    val metaData = MediaMetaData(title = Some("Some Title"))

    val songData = factory.createSongData(Medium, Uri, metaData)
    songData.mediumID should be(Medium)
    songData.uri should be(Uri)
    songData.metaData should be theSameInstanceAs metaData
    songData.resolver should be(factory)
    verifyZeroInteractions(context)
  }

  it should "return a localized unknown album name" in {
    val UnknownAlbum = "Album not known"
    val context = mock[ApplicationContext]
    when(context.getResourceText("unknownAlbum")).thenReturn(UnknownAlbum)
    val factory = new SongDataFactory(context)

    val songData = factory.createSongData(Medium, Uri, MediaMetaData(title = Some("A song")))
    songData.getAlbum should be(UnknownAlbum)
  }

  it should "return a localized unknown artist name" in {
    val UnknownArtist = "Undefined artist"
    val context = mock[ApplicationContext]
    when(context.getResourceText("unknownArtist")).thenReturn(UnknownArtist)
    val factory = new SongDataFactory(context)

    val songData = factory.createSongData(Medium, Uri, MediaMetaData(title = Some("A song")))
    songData.getArtist should be(UnknownArtist)
  }
}
