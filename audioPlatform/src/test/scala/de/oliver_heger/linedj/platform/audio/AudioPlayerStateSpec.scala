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

package de.oliver_heger.linedj.platform.audio

import de.oliver_heger.linedj.player.engine.{AudioSourceID, AudioSourcePlaylistInfo}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.scalatest.{FlatSpec, Matchers}

object AudioPlayerStateSpec {
  /** A test medium ID. */
  private val TestMedium = MediumID("test://medium", Some("path"))

  /**
    * Creates a test source object for the playlist.
    *
    * @param index the index
    * @return the test instance derived from this index
    */
  private def createSource(index: Int): AudioSourcePlaylistInfo =
    AudioSourcePlaylistInfo(AudioSourceID(TestMedium, "song://Test" + index),
      index, index)

  /**
    * Generates a list with the test audio sources in the specified range.
    *
    * @param start the from index (including)
    * @param end   the to index (including)
    * @return the list with test sources
    */
  private def createSources(start: Int, end: Int): List[AudioSourcePlaylistInfo] =
    (start to end).map(createSource).toList
}

/**
  * Test class for ''AudioPlayerState''.
  */
class AudioPlayerStateSpec extends FlatSpec with Matchers {

  import AudioPlayerStateSpec._

  "An AudioPlayerState" should "return the current song" in {
    val state = AudioPlayerState(pendingSongs = createSources(1, 10),
      playedSongs = Nil, playbackActive = false, playlistClosed = false)

    state.currentSong should be(Some(createSource(1)))
  }

  it should "return None if there is no current song" in {
    val state = AudioPlayerState(pendingSongs = Nil, playedSongs = createSources(1, 10),
      playbackActive = true, playlistClosed = false)

    state.currentSong shouldBe 'empty
  }

  it should "move to the next song in the playlist" in {
    val state = AudioPlayerState(playedSongs = createSources(1, 4),
      pendingSongs = createSources(5, 10), playbackActive = true, playlistClosed = false)

    val next = state.moveToNext
    next.playbackActive should be(state.playbackActive)
    next.playlistClosed should be(state.playlistClosed)
    next.playedSongs should be(createSource(5) :: state.playedSongs)
    next.pendingSongs should be(createSources(6, 10))
  }

  it should "handle the end of the playlist when moving to the next song" in {
    val state = AudioPlayerState(playedSongs = createSources(1, 4),
      pendingSongs = Nil, playbackActive = true, playlistClosed = true)

    state.moveToNext should be theSameInstanceAs state
  }

  it should "move to the previous song in the playlist" in {
    val state = AudioPlayerState(playedSongs = createSources(1, 4),
      pendingSongs = createSources(5, 10), playbackActive = false, playlistClosed = true)

    val prev = state.moveToPrev
    prev.playbackActive should be(state.playbackActive)
    prev.playlistClosed should be(state.playlistClosed)
    prev.playedSongs should be(createSources(2, 4))
    prev.pendingSongs should be(createSource(1) :: state.pendingSongs)
  }

  it should "handle the beginning of the playlist when moving to the previous song" in {
    val state = AudioPlayerState(playbackActive = true, playedSongs = Nil,
      pendingSongs = createSources(1, 2), playlistClosed = false)

    state.moveToPrev should be theSameInstanceAs state
  }
}
