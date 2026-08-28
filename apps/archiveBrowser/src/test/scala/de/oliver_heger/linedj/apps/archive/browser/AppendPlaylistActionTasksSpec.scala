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

package de.oliver_heger.linedj.apps.archive.browser

import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.audio2.AudioPlayerCommands
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util

object AppendPlaylistActionTasksSpec:
  /**
    * Generates the ID of a test song based on a provided index.
    *
    * @param index the index of the test song
    * @return the ID of this test song
    */
  private def testSongID(index: Int): String = s"song$index"

  /**
    * Generates a test song based on a provided given index.
    *
    * @param index the index of the test song
    * @return the test song for this index
    */
  private def testSong(index: Int): SongData =
    SongData(MediaMetadata(checksum = testSongID(index), size = index * 100))

  /**
    * Returns a collection with test songs for the indices from 1 to count.
    *
    * @param count the number of test songs
    * @return the collection with the test songs
    */
  private def generateTestSongs(count: Int): Iterable[SongData] =
    (1 to count).map(testSong)
end AppendPlaylistActionTasksSpec

/**
  * Test class for the action tasks to appends songs to the playlist.
  */
class AppendPlaylistActionTasksSpec extends AnyFlatSpec, Matchers, MockitoSugar:

  import AppendPlaylistActionTasksSpec.*

  "AppendTableSongsTask" should "publish a message with all songs in the table" in :
    val songs = generateTestSongs(3)
    val tableModel = new util.ArrayList[AnyRef]
    songs.foreach(tableModel.add)
    val tableHandler = mock[TableHandler]
    doReturn(tableModel).when(tableHandler).getModel
    val bus = new MessageBusTestImpl

    val task = new AppendTableSongsTask(bus, tableHandler, selectedOnly = false)
    task.run()

    val expectedCommand = AudioPlayerCommands.AppendPlaylist(List(testSongID(1), testSongID(2), testSongID(3)))
    bus.expectMessageType[AudioPlayerCommands] should be(expectedCommand)

  it should "publish a message with only the selected songs in the table" in :
    val songs = generateTestSongs(16)
    val selectedIndices = Array(0, 2, 3, 5, 7, 8, 14)
    val tableModel = new util.ArrayList[AnyRef]
    songs.foreach(tableModel.add)
    val tableHandler = mock[TableHandler]
    doReturn(tableModel).when(tableHandler).getModel
    doReturn(selectedIndices).when(tableHandler).getSelectedIndices
    val bus = new MessageBusTestImpl

    val task = new AppendTableSongsTask(bus, tableHandler, selectedOnly = true)
    task.run()

    val expectedCommand = AudioPlayerCommands.AppendPlaylist(
      List(testSongID(1), testSongID(3), testSongID(4), testSongID(6), testSongID(8), testSongID(9), testSongID(15))
    )
    bus.expectMessageType[AudioPlayerCommands] should be(expectedCommand)
