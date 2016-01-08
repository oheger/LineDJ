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

package de.oliver_heger.linedj.browser.media

import java.util

import de.oliver_heger.linedj.client.model.{AppendSongs, SongData}
import de.oliver_heger.linedj.client.remoting.MessageBus
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import org.mockito.Mockito._
import org.scalatest.FlatSpec
import org.scalatest.mock.MockitoSugar

/**
  * Test class for the action tasks that append songs to the playlist.
  */
class AppendPlaylistSpec extends FlatSpec with MockitoSugar {
  "An AppendPlaylistActionTask" should "append all songs of the medium" in {
    val helper = new AppendPlaylistTestHelper
    val task = new AppendMediumActionTask(helper.controller, helper.messageBus)

    helper.verifyActionTask(task)(_.songsForSelectedMedium)
  }

  it should "append all songs of selected artists" in {
    val helper = new AppendPlaylistTestHelper
    val task = new AppendArtistActionTask(helper.controller, helper.messageBus)

    helper.verifyActionTask(task)(_.songsForSelectedArtists)
  }

  it should "append all songs belonging to selected albums" in {
    val helper = new AppendPlaylistTestHelper
    val task = new AppendAlbumActionTask(helper.controller, helper.messageBus)

    helper.verifyActionTask(task)(_.songsForSelectedAlbums)
  }

  it should "append the songs selected in the songs table" in {
    val tableHandler = mock[TableHandler]
    val helper = new AppendPlaylistTestHelper
    val songs = List(mock[SongData], mock[SongData], mock[SongData], mock[SongData])
    val tableModel = util.Arrays.asList(null, songs.head, null, songs(1), songs(2), null, songs(3))
    doReturn(tableModel).when(tableHandler).getModel
    when(tableHandler.getSelectedIndices).thenReturn(Array(1, 3, 4, 6))
    val task = new AppendSongsActionTask(tableHandler, helper.messageBus)

    task.run()
    helper.verifySongsAppended(songs)
  }

  /**
    * A test helper class which collects all required mock objects.
    */
  private class AppendPlaylistTestHelper {

    /** A mock for the message bus. */
    val messageBus = mock[MessageBus]

    /** A mock for the medium controller. */
    val controller = mock[MediaController]

    /**
      * Prepares the mock controller to expect a request for selected songs. The
      * songs in question are selected by the passed in function.
      * @param f the function querying the songs from the controller
      * @return a list with test song data
      */
    def expectSelectionRequest(f: MediaController => Seq[SongData]): Seq[SongData] = {
      val songs = List(mock[SongData], mock[SongData], mock[SongData])
      when(f(controller)).thenReturn(songs)
      songs
    }

    /**
      * Checks whether the expected songs have been appended to the playlist.
      * A corresponding message is searched for on the message bus.
      * @param songs the expected songs
      */
    def verifySongsAppended(songs: Seq[SongData]): Unit = {
      verify(messageBus).publish(AppendSongs(songs))
    }

    /**
      * Verifies that an action task appends the correct songs to the playlist.
      * @param task the task to be tested
      * @param f the function querying the songs from the controller
      */
    def verifyActionTask(task: Runnable)(f: MediaController => Seq[SongData]): Unit = {
      val songs = expectSelectionRequest(f)
      task.run()
      verifySongsAppended(songs)
    }
  }

}
