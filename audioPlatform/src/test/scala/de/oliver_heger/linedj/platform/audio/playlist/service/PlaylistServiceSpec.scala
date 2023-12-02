/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio.playlist.service

import de.oliver_heger.linedj.platform.audio.playlist.Playlist
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.annotation.tailrec

object PlaylistServiceSpec:
  /** A test medium ID. */
  private val TestMedium = MediumID("someMedium", Some("settings"))

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
    * Creates a test playlist item based on the given index.
    *
    * @param idx the index
    * @return the playlist item with this index
    */
  private def item(idx: Int): MediaFileID =
    MediaFileID(TestMedium, s"song://Song$idx.mp3")

  /**
    * Creates a sequence of test songs. Based on this sequence, test
    * playlist instances can be generated easily.
    *
    * @param count the number of songs in the list
    * @return the sequence of test songs
    */
  private def createListOfSongs(count: Int = SongCount): PlaylistService.SongList =
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
  private def differenceAt(songs: PlaylistService.SongList, pos: Int): PlaylistService.SongList =
    val itemToReplace = item(pos + 1)
    songs map { s =>
      if s == itemToReplace then OtherItem else s
    }

/**
  * Test class for ''DefaultPlaylistTrackService''.
  */
class PlaylistServiceSpec extends AnyFlatSpec with Matchers:

  import PlaylistServiceSpec._

  "PlaylistService" should "determine the size of a Playlist" in:
    val pl1 = createPlaylist(4)
    PlaylistService.size(pl1) should be(SongCount)

    val pl2 = Playlist(createListOfSongs(4), Nil)
    PlaylistService.size(pl2) should be(4)

    val pl3 = Playlist(Nil, createListOfSongs(7))
    PlaylistService.size(pl3) should be(7)

  it should "return the current song from a playlist" in:
    val play = createPlaylist(0)

    PlaylistService.currentSong(play) should be(Some(item(1)))

  it should "return None as current song for a playlist completely played" in:
    val play = createPlaylist(SongCount)

    PlaylistService.currentSong(play) should be(None)

  it should "return the index of the current song from a playlist if it is the first" in:
    val Pos = 4
    val play = createPlaylist(Pos)

    PlaylistService.currentIndex(play).get should be(Pos)

  it should "return None for the current index if there is no current song" in:
    val play = createPlaylist(SongCount)

    PlaylistService.currentIndex(play) should be(None)

  it should "move a playlist to the next song if possible" in:
    val play = createPlaylist(0)

    PlaylistService.moveForwards(play) should be(Some(createPlaylist(1)))

  it should "return None in moveForwards() at the end of the playlist" in:
    val play = createPlaylist(SongCount)

    PlaylistService.moveForwards(play) should be(None)

  it should "move a playlist to the previous song if possible" in:
    val play = createPlaylist(3)

    PlaylistService.moveBackwards(play) should be(Some(createPlaylist(2)))

  it should "return None in moveBackwards() at the beginning of the playlist" in:
    val play = createPlaylist(0)

    PlaylistService.moveBackwards(play) should be(None)

  it should "compare playlist instances of different sizes" in:
    val playOrg = createPlaylist(0)
    val play1 = playOrg.copy(pendingSongs = OtherItem :: playOrg.pendingSongs)
    val play2 = createPlaylist(0)

    PlaylistService.playlistEquals(play1, play2) shouldBe false

  it should "correctly compare equal playlist instances" in:
    @tailrec def checkEqualLists(testIdx: Int): Unit =
      if testIdx < SongCount * SongCount then
        val splitPos1 = testIdx / SongCount
        val splitPos2 = testIdx % SongCount
        val play1 = createPlaylist(splitPos1)
        val play2 = createPlaylist(splitPos2)
        PlaylistService.playlistEquals(play1, play2) shouldBe true
        checkEqualLists(testIdx + 1)

    checkEqualLists(0)

  it should "correctly compare non-equal playlist instances" in:
    @tailrec def checkWithDiff(play1: Playlist, play2: Playlist, idx: Int): Unit =
      if idx < SongCount then
        val playComp =
          if idx < play2.playedSongs.size then
            play2.copy(playedSongs = differenceAt(play2.playedSongs, idx))
          else play2.copy(pendingSongs = differenceAt(play2.pendingSongs, idx))
        PlaylistService.playlistEquals(play1, playComp) shouldBe false
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
    PlaylistService.incrementPlaylistSeqNo(PlaylistService.SeqNoInitial) should be(1)
    PlaylistService.incrementPlaylistSeqNo(41) should be(42)

  it should "not return the initial seq number when incrementing" in:
    PlaylistService.incrementPlaylistSeqNo(-1) should be(1)

  it should "provide a transformation to a SongList" in:
    val expectedList = createListOfSongs()

    @tailrec def chekSongList(idx: Int): Unit =
      if idx < SongCount then
        val play = createPlaylist(idx)
        PlaylistService.toSongList(play) should be(expectedList)
        chekSongList(idx + 1)

    chekSongList(0)

  it should "create a Playlist from a sequence of songs" in:
    val CurrentIndex = 10

    val pl = PlaylistService.toPlaylist(createListOfSongs(), CurrentIndex)
    pl should be(Some(createPlaylist(CurrentIndex)))

  it should "handle a negative index when creating a Playlist from a sequence of songs" in:
    PlaylistService.toPlaylist(createListOfSongs(), -1) should be(None)

  it should "handle a too large index when creating a Playlist from a sequence of songs" in:
    PlaylistService.toPlaylist(createListOfSongs(), SongCount) should be(None)

  it should "set the current song of a Playlist" in:
    val CurrentIndex = 8
    val orgPl = createPlaylist(1)

    val pl = PlaylistService.setCurrentSong(orgPl, CurrentIndex)
    pl should be(Some(createPlaylist(CurrentIndex)))
