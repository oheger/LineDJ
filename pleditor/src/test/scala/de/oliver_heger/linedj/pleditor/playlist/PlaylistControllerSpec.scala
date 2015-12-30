/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.playlist

import java.util

import de.oliver_heger.linedj.client.model.{AppendSongs, SongData}
import de.oliver_heger.linedj.media.MediumID
import de.oliver_heger.linedj.metadata.MediaMetaData
import net.sf.jguiraffe.gui.builder.components.model.{StaticTextHandler, TableHandler}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object PlaylistControllerSpec {
  /** A test medium ID. */
  private val Medium = MediumID("someURI", Some("somePath"))

  /** The template for the status line. */
  private val StatusLineTemplate = "Count: %d, Duration: %s, Size: %.2f"

  /**
   * Generates text for the status line based on the specified parameters.
   * @param count the number of items in the playlist
   * @param duration the duration
   * @param size the size
   * @return the text for the status line
   */
  private def generateStatusLine(count: Int, duration: String, size: Double): String =
    StatusLineTemplate.format(count, duration, size)

  /**
   * Creates a song data object for the specified test song.
   * @param title the song title
   * @param duration the duration (in milliseconds)
   * @param size the size (in bytes)
   * @return the ''SongData''
   */
  private def song(title: String, duration: Int, size: Int): SongData =
    SongData(Medium, "song://" + title, MediaMetaData(title = Some(title), duration = Some(duration),
      size = size), null)
}

/**
 * Test class for ''PlaylistController''.
 */
class PlaylistControllerSpec extends FlatSpec with Matchers with MockitoSugar {

  import PlaylistControllerSpec._

  "A PlaylistController" should "add new songs to a playlist" in {
    val song1 = song("A", 60000, 1024 * 1024)
    val song2 = song("B", 120000, 3 * 1024 * 1024)
    val song3 = song("C", 90000, 2 * 1024 * 1024)
    val helper = new PlaylistControllerTestHelper

    helper.appendSongs(song1, song2, song3)
    helper.playlistModel should be(util.Arrays.asList(song1, song2, song3))
    verify(helper.tableHandler).rowsInserted(0, 2)
  }

  it should "append songs to an existing playlist" in {
    val song1 = song("A", 60000, 1024 * 1024)
    val song2 = song("B", 120000, 3 * 1024 * 1024)
    val song3 = song("C", 90000, 2 * 1024 * 1024)
    val helper = new PlaylistControllerTestHelper
    helper.appendSongs(song1, song2)

    helper.appendSongs(song3)
    helper.playlistModel should be(util.Arrays.asList(song1, song2, song3))
    verify(helper.tableHandler).rowsInserted(2, 2)
  }

  it should "update the status line when new songs are added" in {
    val song1 = song("A", 60000, 1024 * 1024)
    val song2 = song("B", 120000, 1572864)
    val helper = new PlaylistControllerTestHelper

    helper.appendSongs(song1, song2)
    verify(helper.statusLineHandler).setText(generateStatusLine(2, "3:00", 2.5))
  }

  it should "output an indication in the status line if there are songs with unknown duration" in {
    val song1 = song("A", 60000, 1024 * 1024)
    val song2 = SongData(Medium, "song://B", MediaMetaData(title = Some("B"), size = 1024 * 1024), null)
    val helper = new PlaylistControllerTestHelper

    helper.appendSongs(song1, song2)
    verify(helper.statusLineHandler).setText(generateStatusLine(2, "> 1:00", 2.0))
  }

  it should "execute a playlist manipulator" in {
    val song1 = song("A", 60000, 1024 * 1024)
    val song2 = song("B", 120000, 1572864)
    val helper = new PlaylistControllerTestHelper
    helper.appendSongs(song1, song2)
    val manipulator = new PlaylistManipulator {
      override def updatePlaylist(context: PlaylistSelectionContext): Unit = {
        context.tableHandler.getModel.remove(1)
      }

      override def isEnabled(context: PlaylistSelectionContext): Boolean = true
    }

    helper.controller updatePlaylist manipulator
    helper.playlistModel should have size 1
    helper.playlistModel should contain only song1
    verify(helper.statusLineHandler).setText(generateStatusLine(1, "1:00", 1.0))
  }

  /**
   * A test helper class managing dependent objects.
   */
  private class PlaylistControllerTestHelper {
    /** The table model. */
    val playlistModel = new util.ArrayList[SongData]

    /** A mock for the table handler. */
    val tableHandler = createTableHandler()

    /** A mock for the status line handler. */
    val statusLineHandler = mock[StaticTextHandler]

    /** The test controller instance. */
    val controller = new PlaylistController(tableHandler, statusLineHandler, StatusLineTemplate)

    /**
     * Adds the given songs to the playlist managed by the controller (in a
     * single chunk).
     * @param songs the songs to be added
     */
    def appendSongs(songs: SongData*): Unit = {
      controller receive AppendSongs(songs)
    }

    /**
     * Creates a mock for the table handler.
     * @return the mock table handler
     */
    private def createTableHandler(): TableHandler = {
      val handler = mock[TableHandler]
      doReturn(playlistModel).when(handler).getModel
      handler
    }
  }

}
