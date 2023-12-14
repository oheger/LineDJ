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

package de.oliver_heger.linedj.pleditor.ui.playlist

import de.oliver_heger.linedj.platform.ActionTestHelper
import de.oliver_heger.linedj.platform.app.ConsumerRegistrationProviderTestHelper

import java.util
import de.oliver_heger.linedj.platform.audio.model.{DefaultSongDataFactory, SongData, UnknownPropertyResolver}
import de.oliver_heger.linedj.platform.audio.playlist.{Playlist, PlaylistMetaData, PlaylistMetaDataRegistration}
import de.oliver_heger.linedj.platform.audio.{AudioPlayerState, AudioPlayerStateChangeRegistration, AudioPlayerStateChangedEvent}
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.model.{StaticTextHandler, TableHandler}
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration.*

object PlaylistControllerSpec:
  /** A test medium ID. */
  private val Medium = MediumID("someURI", Some("somePath"))

  /** The template for the status line. */
  private val StatusLineTemplate = "Count: %d, Duration: %s, Size: %.2f"

  /** Constant for an unknown artist name. */
  private val UnknownArtist = "Unknown artist"

  /** Constant for an unknown album name. */
  private val UnknownAlbum = "Unknown album"

  /** The names of actions related to the current playlist. */
  private val PlaylistActions = List("plExportAction", "plActivateAction")

  /** A test song factory. */
  private val SongFactory = new DefaultSongDataFactory(new UnknownPropertyResolver {
    override def resolveAlbumName(songID: MediaFileID): String = UnknownAlbum

    override def resolveArtistName(songID: MediaFileID): String = UnknownArtist
  })

  /**
   * Generates text for the status line based on the specified parameters.
    *
    * @param count the number of items in the playlist
   * @param duration the duration
   * @param size the size
   * @return the text for the status line
   */
  private def generateStatusLine(count: Int, duration: String, size: Double): String =
    StatusLineTemplate.format(count, duration, size)

  /**
    * Generates the ID of a test media file based on the given index.
    *
    * @param idx the index
    * @return the ID of this test file
    */
  private def fileID(idx: Int): MediaFileID =
    MediaFileID(Medium, "testSong" + idx)

  /**
    * Generates meta data for a test song based on the given index.
    *
    * @param idx the index
    * @return meta data for this test song
    */
  private def metaData(idx: Int): MediaMetaData =
    MediaMetaData(title = Some("testSong" + idx), artist = Some("testArtist" + idx),
      duration = Some((idx + 1).minutes.toMillis.toInt), size = (idx + 1) * 1024 * 512)

  /**
    * Creates a ''SongData'' object with the specified parameters.
    *
    * @param idx  the index of the test song
    * @param meta the meta data for this song
    * @return the ''SongData'' instance
    */
  private def songData(idx: Int, meta: MediaMetaData): SongData =
    SongFactory.createSongData(fileID(idx), meta)

  /**
    * Creates a ''SongData'' object whose meta data has not yet been resolved.
    *
    * @param idx the index of the test song
    * @return the ''SongData'' instance
    */
  private def unresolvedSongData(idx: Int): SongData =
    songData(idx, PlaylistController.UndefinedMetaData)

  /**
    * Creates a ''SongData'' object with resolved meta data.
    *
    * @param idx the index of the test song
    * @return the ''SongData'' instance
    */
  private def resolvedSongData(idx: Int): SongData =
    songData(idx, metaData(idx))

  /**
    * Generates a list of file IDs in the specified range.
    *
    * @param from the start index
    * @param to   the end index (including)
    * @return the list with song IDs
    */
  private def fileIDs(from: Int, to: Int): List[MediaFileID] =
    (from to to).map(fileID).toList

  /**
    * Generates a map with meta data for the songs of a playlist in the given
    * range.
    *
    * @param from the start index
    * @param to   the end index (including)
    * @return the map with meta data
    */
  private def playlistMetaData(from: Int, to: Int): Map[MediaFileID, MediaMetaData] =
    (from to to).foldLeft(Map.empty[MediaFileID, MediaMetaData]) { (m, i) =>
      m + (fileID(i) -> metaData(i))
    }

/**
 * Test class for ''PlaylistController''.
 */
class PlaylistControllerSpec extends AnyFlatSpec with Matchers:

  import PlaylistControllerSpec._

  "A PlaylistController" should "add new songs to a playlist" in:
    val songs = fileIDs(0, 3)
    val helper = new PlaylistControllerTestHelper

    helper.addSongs(songs)
      .expectUnresolvedSongs(0, 3)
    verify(helper.tableHandler).rowsInserted(0, 3)

  it should "append songs to an existing playlist" in:
    val helper = new PlaylistControllerTestHelper
    helper.addSongs(fileIDs(0, 1))

    helper.addSongs(fileIDs(0, 2))
      .expectUnresolvedSongs(0, 2)
    verify(helper.tableHandler).rowsInserted(2, 2)

  it should "resolve newly added songs if possible" in:
    val helper = new PlaylistControllerTestHelper

    helper.sendPlaylistMetaData(playlistMetaData(2, 4))
      .addSongs(fileIDs(0, 7))
      .expectUnresolvedSongs(0, 1)
      .expectUnresolvedSongs(5, 7)
      .expectResolvedSongs(2, 4)

  it should "resolve songs when new meta data arrives" in:
    val helper = new PlaylistControllerTestHelper

    helper.addSongs(fileIDs(0, 7))
      .sendPlaylistMetaData(playlistMetaData(2, 4))
      .expectUnresolvedSongs(0, 1)
      .expectUnresolvedSongs(5, 7)
      .expectResolvedSongs(2, 4)
    verify(helper.tableHandler).rowsUpdated(2, 4)

  it should "ignore meta data updates if all songs have been resolved initially" in:
    val helper = new PlaylistControllerTestHelper

    helper.sendPlaylistMetaData(playlistMetaData(0, 3))
      .addSongs(fileIDs(0, 3))
      .resetToUnresolvedSongs()
      .sendPlaylistMetaData(playlistMetaData(0, 4))
      .expectUnresolvedSongs(0, 3)

  it should "ignore meta data updates if all songs have been resolved later" in:
    val helper = new PlaylistControllerTestHelper

    helper.addSongs(fileIDs(0, 3))
      .sendPlaylistMetaData(playlistMetaData(0, 3))
      .resetToUnresolvedSongs()
      .sendPlaylistMetaData(playlistMetaData(0, 4))
      .expectUnresolvedSongs(0, 3)

  it should "handle a meta data update if no new songs are resolved" in:
    val helper = new PlaylistControllerTestHelper
    helper.sendPlaylistMetaData(playlistMetaData(0, 3))
      .addSongs(fileIDs(0, 7))
      .sendPlaylistMetaData(playlistMetaData(8, 10))

    verify(helper.tableHandler, never()).rowsUpdated(anyInt(), anyInt())

  it should "update the status line when new songs are added" in:
    val helper = new PlaylistControllerTestHelper
    helper.sendPlaylistMetaData(playlistMetaData(0, 1)).addSongs(fileIDs(0, 1))

    verify(helper.statusLineHandler).setText(generateStatusLine(2, "3:00", 1.5))

  it should "output an indication in the status line if there are songs with unknown duration" in:
    val helper = new PlaylistControllerTestHelper
    helper.sendPlaylistMetaData(playlistMetaData(0, 0))
      .addSongs(fileIDs(0, 1))

    verify(helper.statusLineHandler).setText(generateStatusLine(2, "> 1:00", 0.5))

  it should "update the status line when new meta data arrives" in:
    val helper = new PlaylistControllerTestHelper
    helper.addSongs(fileIDs(0, 1))
      .sendPlaylistMetaData(playlistMetaData(0, 1))

    verify(helper.statusLineHandler).setText(generateStatusLine(2, "3:00", 1.5))

  it should "select newly added songs" in:
    val helper = new PlaylistControllerTestHelper
    helper.addSongs(fileIDs(0, 0))
    verify(helper.tableHandler).setSelectedIndices(Array(0))

    helper.addSongs(fileIDs(0, 2))
    verify(helper.tableHandler).setSelectedIndices(Array(1, 2))

  it should "enable the playlist actions if songs are added" in:
    val helper = new PlaylistControllerTestHelper

    helper.addSongs(fileIDs(0, 0))
      .verifyPlaylistActions(enabled = true)

  it should "execute a playlist manipulator" in:
    val helper = new PlaylistControllerTestHelper
    helper.sendPlaylistMetaData(playlistMetaData(0, 0))
      .addSongs(fileIDs(0, 1))
    val manipulator = new PlaylistManipulator:
      override def updatePlaylist(context: PlaylistSelectionContext): Unit =
        context.tableHandler.getModel.remove(1)

      override def isEnabled(context: PlaylistSelectionContext): Boolean = true

    helper.controller updatePlaylist manipulator
    helper.playlistModel should have size 1
    helper.expectResolvedSongs(0, 0)
    verify(helper.statusLineHandler).setText(generateStatusLine(1, "1:00", 0.5))

  it should "update actions after the playlist has been manipulated" in:
    val helper = new PlaylistControllerTestHelper
    helper addSongs fileIDs(0, 0)
    val manipulator = new PlaylistManipulator:
      override def updatePlaylist(context: PlaylistSelectionContext): Unit =
        context.tableHandler.getModel.clear()

      override def isEnabled(context: PlaylistSelectionContext): Boolean = true

    helper.controller updatePlaylist manipulator
    helper.verifyPlaylistActions(enabled = false)

  it should "update the unresolved song counter after a manipulation of the playlist" in:
    val helper = new PlaylistControllerTestHelper
    helper.sendPlaylistMetaData(playlistMetaData(0, 0))
      .addSongs(fileIDs(0, 0))
    val manipulator = new PlaylistManipulator:
      override def updatePlaylist(context: PlaylistSelectionContext): Unit =
        context.tableHandler.getModel add resolvedSongData(1)

      override def isEnabled(context: PlaylistSelectionContext): Boolean = true

    helper.controller updatePlaylist manipulator
    helper.resetToUnresolvedSongs()
      .sendPlaylistMetaData(playlistMetaData(0, 1))
      .expectResolvedSongs(0, 1)

  /**
   * A test helper class managing dependent objects.
   */
  private class PlaylistControllerTestHelper extends ActionTestHelper with MockitoSugar:
    /** The table model. */
    val playlistModel = new util.ArrayList[SongData]

    /** A mock for the table handler. */
    val tableHandler: TableHandler = createTableHandler()

    /** A mock for the status line handler. */
    val statusLineHandler: StaticTextHandler = mock[StaticTextHandler]

    /** The test controller instance. */
    val controller = new PlaylistController(tableHandler, statusLineHandler, initActions(),
      StatusLineTemplate, SongFactory)

    /**
      * Passes a player state change notification to the test controller with
      * a playlist containing the specified songs.
      *
      * @param ids the IDs of the songs in the playlist
      * @return this test helper
      */
    def addSongs(ids: List[MediaFileID]): PlaylistControllerTestHelper =
      val playlist = Playlist(pendingSongs = ids, playedSongs = Nil)
      val stateEvent = AudioPlayerStateChangedEvent(AudioPlayerState(playlist = playlist,
        playlistSeqNo = 1, playbackActive = false, playlistClosed = false,
        playlistActivated = true))
      ConsumerRegistrationProviderTestHelper
        .findRegistration[AudioPlayerStateChangeRegistration](controller)
        .callback(stateEvent)
      this

    /**
      * Passes a map with meta data to the test controller.
      *
      * @param data the map with meta data
      * @return this test helper
      */
    def sendPlaylistMetaData(data: Map[MediaFileID, MediaMetaData]):
    PlaylistControllerTestHelper =
      ConsumerRegistrationProviderTestHelper
        .findRegistration[PlaylistMetaDataRegistration](controller)
        .callback(PlaylistMetaData(data))
      this

    /**
      * Checks that the table model contains resolved songs in the specified
      * range.
      *
      * @param from the start index
      * @param to   the end index (including)
      * @return this test helper
      */
    def expectResolvedSongs(from: Int, to: Int): PlaylistControllerTestHelper =
      checkSongsInModel(from, to)(resolvedSongData)

    /**
      * Checks that the table model contains unresolved songs in the specified
      * range.
      *
      * @param from the start index
      * @param to   the end index (including)
      * @return this test helper
      */
    def expectUnresolvedSongs(from: Int, to: Int): PlaylistControllerTestHelper =
      checkSongsInModel(from, to)(unresolvedSongData)

    /**
      * Resets all SongData objects in the table model to unresolved ones.
      *
      * @return this test helper
      */
    def resetToUnresolvedSongs(): PlaylistControllerTestHelper =
      val count = playlistModel.size()
      playlistModel.clear()
      (0 until count) foreach (playlistModel add unresolvedSongData(_))
      this

    /**
      * Checks whether the actions depending on the presence of songs in the
      * playlist have the expected enabled state.
      *
      * @param enabled the expected enabled state
      * @return this test helper
      */
    def verifyPlaylistActions(enabled: Boolean): PlaylistControllerTestHelper =
      PlaylistActions foreach (isActionEnabled(_) shouldBe enabled)
      this

    /**
      * Checks that the table model contains specific songs in the specified
      * range. For each index in the range the provided song function is
      * called, and the resulting song is compared with the song in the table
      * model.
      *
      * @param from the start index
      * @param to   the end index (including)
      * @param sf   the function to generate expected songs
      * @return this test helper
      */
    private def checkSongsInModel(from: Int, to: Int)(sf: Int => SongData):
    PlaylistControllerTestHelper =
      (from to to) foreach { i =>
        playlistModel.get(i) should be(sf(i))
      }
      this

    /**
     * Creates a mock for the table handler.
      *
      * @return the mock table handler
     */
    private def createTableHandler(): TableHandler =
      val handler = mock[TableHandler]
      doReturn(playlistModel).when(handler).getModel
      handler

    /**
      * Creates a mock action store which returns the actions to be managed
      * by the controller.
      *
      * @return the mock action store
      */
    private def initActions(): ActionStore =
      createActions(PlaylistActions: _*)
      createActionStore()

