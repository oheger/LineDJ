/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio2.impl

import de.oliver_heger.linedj.platform.audio2.playlist.{Playlist, PlaylistService}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.annotation.tailrec

object PlaylistServiceImplSpec:
  /** The number of songs in the test playlist. */
  private val SongCount = 16

  /** A list with all songs contained in test playlist instances. */
  private val SongList = createListOfSongs()

  /**
    * Constant for a different item, which is not contained in the standard
    * test playlist instances. This is used to test real differences.
    */
  private val OtherItem = item(SongCount * SongCount)

  /**
    * Creates a test playlist item (a song ID) based on the given index.
    *
    * @param idx the index
    * @return the playlist item with this index
    */
  private def item(idx: Int): String = s"song://Song$idx.mp3"

  /**
    * Creates a sequence of test songs. Based on this sequence, test
    * playlist instances can be generated easily.
    *
    * @param count the number of songs in the list
    * @return the sequence of test songs
    */
  private def createListOfSongs(count: Int = SongCount): PlaylistServiceImpl.SongList =
    (1 to count).map(item).toList

  /**
    * Generates a test playlist with the specified current position.
    *
    * @param position the position
    * @return the ''Playlist''
    */
  private def createPlaylist(position: Int): Playlist =
    val (played, pending) = SongList.splitAt(position)
    Playlist(pendingSongs = pending, playedSongs = played.reverse)

  /**
    * Generates a playlist that contains a different song at the specified
    * position. This is used to test whether differences can be detected at all
    * positions.
    *
    * @param songs the original list of songs
    * @param pos   the index of the item to be replaced
    * @return the manipulated list of songs
    */
  private def differenceAt(songs: PlaylistServiceImpl.SongList, pos: Int): PlaylistServiceImpl.SongList =
    val itemToReplace = item(pos + 1)
    songs map : s =>
      if s == itemToReplace then OtherItem else s
end PlaylistServiceImplSpec

/**
  * Test class for [[PlaylistServiceImpl]].
  */
class PlaylistServiceImplSpec extends AnyFlatSpec with Matchers:

  import PlaylistServiceImplSpec.*

  "PlaylistServiceImpl" should "determine the size of a Playlist" in:
    val pl1 = createPlaylist(4)
    PlaylistServiceImpl.size(pl1) should be(SongCount)

    val pl2 = Playlist(createListOfSongs(4), Nil)
    PlaylistServiceImpl.size(pl2) should be(4)

    val pl3 = Playlist(Nil, createListOfSongs(7))
    PlaylistServiceImpl.size(pl3) should be(7)

  it should "return the current song from a playlist" in:
    val play = createPlaylist(0)

    PlaylistServiceImpl.currentSong(play) should be(Some(item(1)))

  it should "return None as current song for a playlist completely played" in:
    val play = createPlaylist(SongCount)

    PlaylistServiceImpl.currentSong(play) should be(None)

  it should "return the index of the current song from a playlist if it is the first" in:
    val Pos = 4
    val play = createPlaylist(Pos)

    PlaylistServiceImpl.currentIndex(play).get should be(Pos)

  it should "return None for the current index if there is no current song" in:
    val play = createPlaylist(SongCount)

    PlaylistServiceImpl.currentIndex(play) should be(None)

  it should "move a playlist to the next song if possible" in:
    val play = createPlaylist(0)

    PlaylistServiceImpl.moveForwards(play) should be(Some(createPlaylist(1)))

  it should "return None in moveForwards() at the end of the playlist" in:
    val play = createPlaylist(SongCount)

    PlaylistServiceImpl.moveForwards(play) should be(None)

  it should "move a playlist to the previous song if possible" in:
    val play = createPlaylist(3)

    PlaylistServiceImpl.moveBackwards(play) should be(Some(createPlaylist(2)))

  it should "return None in moveBackwards() at the beginning of the playlist" in:
    val play = createPlaylist(0)

    PlaylistServiceImpl.moveBackwards(play) should be(None)

  it should "compare playlist instances of different sizes" in:
    val playOrg = createPlaylist(0)
    val play1 = playOrg.copy(pendingSongs = OtherItem :: playOrg.pendingSongs)
    val play2 = createPlaylist(0)

    PlaylistServiceImpl.playlistEquals(play1, play2) shouldBe false

  it should "correctly compare equal playlist instances" in:
    @tailrec def checkEqualLists(testIdx: Int): Unit =
      if testIdx < SongCount * SongCount then
        val splitPos1 = testIdx / SongCount
        val splitPos2 = testIdx % SongCount
        val play1 = createPlaylist(splitPos1)
        val play2 = createPlaylist(splitPos2)
        PlaylistServiceImpl.playlistEquals(play1, play2) shouldBe true
        checkEqualLists(testIdx + 1)

    checkEqualLists(0)

  it should "correctly compare non-equal playlist instances" in:
    @tailrec def checkWithDiff(play1: Playlist, play2: Playlist, idx: Int): Unit =
      if idx < SongCount then
        val playComp =
          if idx < play2.playedSongs.size then
            play2.copy(playedSongs = differenceAt(play2.playedSongs, idx))
          else play2.copy(pendingSongs = differenceAt(play2.pendingSongs, idx))
        PlaylistServiceImpl.playlistEquals(play1, playComp) shouldBe false
        checkWithDiff(play1, play2, idx + 1)

    @tailrec def checkNonEqualLists(testIdx: Int): Unit =
      if testIdx < SongCount * SongCount then
        val splitPos1 = testIdx / SongCount
        val splitPos2 = testIdx % SongCount
        val play1 = createPlaylist(splitPos1)
        val play2 = createPlaylist(splitPos2)
        checkWithDiff(play1, play2, 0)
        checkNonEqualLists(testIdx + 1)

    checkNonEqualLists(0)

  it should "increment a playlist sequence number" in:
    PlaylistServiceImpl.incrementPlaylistSeqNo(PlaylistService.SeqNoInitial) should be(1)
    PlaylistServiceImpl.incrementPlaylistSeqNo(41) should be(42)

  it should "not return the initial seq number when incrementing" in:
    PlaylistServiceImpl.incrementPlaylistSeqNo(-1) should be(1)

  it should "provide a transformation to a SongList" in:
    val expectedList = createListOfSongs()

    @tailrec def chekSongList(idx: Int): Unit =
      if idx < SongCount then
        val play = createPlaylist(idx)
        PlaylistServiceImpl.toSongList(play) should be(expectedList)
        chekSongList(idx + 1)

    chekSongList(0)

  it should "create a Playlist from a sequence of songs" in:
    val CurrentIndex = 10

    val pl = PlaylistServiceImpl.toPlaylist(createListOfSongs(), CurrentIndex)
    pl should be(Some(createPlaylist(CurrentIndex)))

  it should "handle a negative index when creating a Playlist from a sequence of songs" in:
    PlaylistServiceImpl.toPlaylist(createListOfSongs(), -1) should be(None)

  it should "handle a too large index when creating a Playlist from a sequence of songs" in:
    PlaylistServiceImpl.toPlaylist(createListOfSongs(), SongCount) should be(None)

  it should "set the current song of a Playlist" in:
    val CurrentIndex = 8
    val orgPl = createPlaylist(1)

    val pl = PlaylistServiceImpl.setCurrentSong(orgPl, CurrentIndex)
    pl should be(Some(createPlaylist(CurrentIndex)))
