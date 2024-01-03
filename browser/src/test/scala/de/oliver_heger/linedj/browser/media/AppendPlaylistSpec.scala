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

package de.oliver_heger.linedj.browser.media

import java.util

import de.oliver_heger.linedj.platform.audio.AppendPlaylist
import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.immutable.Seq

/**
  * Test class for the action tasks that append songs to the playlist.
  */
class AppendPlaylistSpec extends AnyFlatSpec with MockitoSugar:
  /**
    * Creates the ID of a test song based on the given index.
    *
    * @param idx the index of the song
    * @return the ID of this test song
    */
  def songID(idx: Int): MediaFileID =
    MediaFileID(MediumID("testMedium", None), "testSong" + idx)

  /**
    * Creates a mock ''SongData'' object for the test song with the given
    * index.
    *
    * @param idx the index of the song
    * @return the mock for this test song
    */
  def mockSong(idx: Int): SongData =
    val song = mock[SongData]
    when(song.id).thenReturn(songID(idx))
    song

  "An AppendPlaylistActionTask" should "append all songs of the medium" in:
    val helper = new AppendPlaylistTestHelper
    val task = new AppendMediumActionTask(helper.controller, helper.messageBus)

    helper.verifyActionTask(task)(_.songsForSelectedMedium)

  it should "append all songs of selected artists" in:
    val helper = new AppendPlaylistTestHelper
    val task = new AppendArtistActionTask(helper.controller, helper.messageBus)

    helper.verifyActionTask(task)(_.songsForSelectedArtists)

  it should "append all songs belonging to selected albums" in:
    val helper = new AppendPlaylistTestHelper
    val task = new AppendAlbumActionTask(helper.controller, helper.messageBus)

    helper.verifyActionTask(task)(_.songsForSelectedAlbums)

  it should "append the songs selected in the songs table" in:
    val SongCount = 8
    val selectedSongs = Array(1, 3, 4, 6)
    val tableHandler = mock[TableHandler]
    val helper = new AppendPlaylistTestHelper
    val tableModel = new util.ArrayList[AnyRef](SongCount)
    (0 until SongCount) foreach (i => tableModel add mockSong(i))
    doReturn(tableModel).when(tableHandler).getModel
    when(tableHandler.getSelectedIndices).thenReturn(selectedSongs)
    val task = new AppendSongsActionTask(tableHandler, helper.messageBus)

    task.run()
    helper.verifySongsAppended(selectedSongs.map(songID).toList)

  /**
    * A test helper class which collects all required mock objects.
    */
  private class AppendPlaylistTestHelper:

    /** A mock for the message bus. */
    val messageBus: MessageBus = mock[MessageBus]

    /** A mock for the medium controller. */
    val controller: MediaController = mock[MediaController]

    /**
      * Prepares the mock controller to expect a request for selected songs. The
      * songs in question are selected by the passed in function.
      * @param f the function querying the songs from the controller
      * @return a list with IDs of selected test songs
      */
    def expectSelectionRequest(f: MediaController => Seq[SongData]): List[MediaFileID] =
      val songIDs = List(1, 2, 5, 8, 19)
      val songs = songIDs map mockSong
      when(f(controller)).thenReturn(songs)
      songIDs map songID

    /**
      * Checks whether the expected songs have been appended to the playlist.
      * A corresponding message is searched for on the message bus.
      * @param songs the expected songs
      */
    def verifySongsAppended(songs: List[MediaFileID]): Unit =
      verify(messageBus).publish(AppendPlaylist(songs, activate = false))

    /**
      * Verifies that an action task appends the correct songs to the playlist.
      * @param task the task to be tested
      * @param f the function querying the songs from the controller
      */
    def verifyActionTask(task: Runnable)(f: MediaController => Seq[SongData]): Unit =
      val songs = expectSelectionRequest(f)
      task.run()
      verifySongsAppended(songs)

