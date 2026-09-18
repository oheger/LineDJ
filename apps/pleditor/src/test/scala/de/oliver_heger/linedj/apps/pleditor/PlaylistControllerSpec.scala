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

package de.oliver_heger.linedj.apps.pleditor

import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.platform.ActionTestHelper
import de.oliver_heger.linedj.platform.audio2.AudioPlayerState
import de.oliver_heger.linedj.platform.audio2.playlist.{MediaFileInfoResolverService, Playlist}
import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.model.{StaticTextHandler, TableHandler}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{anyInt, eq as argEq}
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

object PlaylistControllerSpec:
  /** A test medium ID. */
  private val Medium = MediumID("someURI", Some("somePath"))

  /** The template for the status line. */
  private val StatusLineTemplate = "Count: %d, Duration: %s, Size: %.2f"

  /** Constant for an unknown artist name. */
  private val UnknownArtist = "Unknown artist"

  /** Constant for an unknown album name. */
  private val UnknownAlbum = "Unknown album"

  /** The text to show in the status line in case of an error. */
  private val StatusLineErrorText = "Error when resolving songs"

  /** The names of actions related to the current playlist. */
  private val PlaylistActions = List("plExportAction", "plActivateAction")

  /**
    * Generates text for the status line based on the specified parameters.
    *
    * @param count    the number of items in the playlist
    * @param duration the duration
    * @param size     the size
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
  private def fileID(idx: Int): String = "testSong" + idx

  /**
    * Generates metadata for a test song based on the given index.
    *
    * @param idx the index
    * @return metadata for this test song
    */
  private def metadata(idx: Int): MediaMetadata =
    MediaMetadata(
      title = Some("testSong" + idx),
      artist = Some("testArtist" + idx),
      duration = Some((idx + 1).minutes.toMillis.toInt),
      size = (idx + 1) * 1024 * 512,
      checksum = fileID(idx)
    )

  /**
    * Generates a URI for a test media file based on an index.
    *
    * @param idx the index of the test file
    * @return the URI for this test media file
    */
  private def fileUri(idx: Int): MediaFileUri =
    MediaFileUri(s"/test/songs/test_song$idx.mp3")

  /**
    * Generates an object with information about a test media file based on an
    * index.
    *
    * @param idx the index of the test media file
    * @return the info object for this file
    */
  private def fileInfo(idx: Int): ArchiveModel.MediaFileInfo =
    ArchiveModel.MediaFileInfo(
      metadata = metadata(idx),
      fileUri = fileUri(idx),
      mediumID = Checksums.MediumChecksum("test-medium-id")
    )

  /**
    * Creates a [[SongData]] object whose metadata has not yet been resolved.
    *
    * @param idx the index of the test song
    * @return the [[SongData]] instance
    */
  private def unresolvedSongData(idx: Int): SongData = SongData(fileID(idx), None)

  /**
    * Creates a [[SongData]] object with resolved metadata.
    *
    * @param idx the index of the test song
    * @return the [[SongData]] instance
    */
  private def resolvedSongData(idx: Int): SongData = SongData(fileID(idx), Some(fileInfo(idx)))

  /**
    * Generates a list of file IDs in the specified range.
    *
    * @param from the start index
    * @param to   the end index (including)
    * @return the list with song IDs
    */
  private def fileIDs(from: Int, to: Int): List[String] =
    (from to to).map(fileID).toList

  /**
    * Generates a map with information for the files of a playlist in the given
    * range.
    *
    * @param from the start index
    * @param to   the end index (including)
    * @return the map with file information
    */
  private def fileInfoMap(from: Int, to: Int): Map[String, ArchiveModel.MediaFileInfo] =
    (from to to).foldLeft(Map.empty[String, ArchiveModel.MediaFileInfo]): (m, i) =>
      m + (fileID(i) -> fileInfo(i))
end PlaylistControllerSpec

/**
  * Test class for [[PlaylistController]].
  */
class PlaylistControllerSpec extends AnyFlatSpec with Matchers:

  import PlaylistControllerSpec.*

  "A PlaylistController" should "add new songs to a playlist" in :
    val songs = fileIDs(0, 3)
    val helper = new PlaylistControllerTestHelper

    helper.addSongs(songs)
      .expectUnresolvedSongs(0, 3)
    verify(helper.tableHandler).rowsInserted(0, 3)

  it should "append songs to an existing playlist" in :
    val helper = new PlaylistControllerTestHelper
    helper.addSongs(fileIDs(0, 1))

    helper.addSongs(fileIDs(0, 2))
      .expectUnresolvedSongs(0, 2)
    verify(helper.tableHandler).rowsInserted(2, 2)

  it should "resolve newly added songs" in :
    val newSongs = fileIDs(0, 7)
    val helper = new PlaylistControllerTestHelper

    helper.addSongs(newSongs)
      .handleSuccessFileInfoRequest(newSongs, fileInfoMap(2, 4))
      .expectUnresolvedSongs(0, 1)
      .expectUnresolvedSongs(5, 7)
      .expectResolvedSongs(2, 4)
    verify(helper.tableHandler).rowsUpdated(2, 4)

  it should "ignore file information for unknown songs" in :
    val newSongs = fileIDs(0, 3)
    val helper = new PlaylistControllerTestHelper

    helper.addSongs(newSongs)
      .handleSuccessFileInfoRequest(newSongs, fileInfoMap(0, 15))
      .expectResolvedSongs(0, 3)
    verify(helper.tableHandler).rowsUpdated(0, 3)

  it should "update the status line when new songs are added" in :
    val newSongs = fileIDs(0, 1)
    val helper = new PlaylistControllerTestHelper
    helper.addSongs(newSongs)

    verify(helper.statusLineHandler).setText(generateStatusLine(2, "> 0:00", 0))

  it should "output an indication in the status line if there are songs with unknown duration" in :
    val newSongs = fileIDs(0, 1)
    val helper = new PlaylistControllerTestHelper
    helper.addSongs(newSongs)
      .handleSuccessFileInfoRequest(newSongs, fileInfoMap(0, 0))

    verify(helper.statusLineHandler).setText(generateStatusLine(2, "> 0:00", 0))

  it should "update the status line when new metadata arrives" in :
    val newSongs = fileIDs(0, 1)
    val helper = new PlaylistControllerTestHelper
    helper.addSongs(newSongs)
      .handleSuccessFileInfoRequest(newSongs, fileInfoMap(0, 1))

    verify(helper.statusLineHandler).setText(generateStatusLine(2, "3:00", 1.5))

  it should "not request file information if no new songs are added" in :
    val state = AudioPlayerState(
      playlist = Playlist(Nil, Nil),
      playlistSeqNo = 1,
      playbackActive = true,
      playlistClosed = false,
      playlistActivated = true
    )
    val helper = new PlaylistControllerTestHelper

    helper.controller.receive(state)

    helper.verifyNoFileInfoRequest()

  it should "not update the table if no new metadata was received" in :
    val newSongs = fileIDs(0, 7)
    val helper = new PlaylistControllerTestHelper

    helper.addSongs(newSongs)
      .handleSuccessFileInfoRequest(newSongs, fileInfoMap(8, 16))

    verify(helper.tableHandler, never()).rowsUpdated(anyInt(), anyInt())

  it should "show an error text in the status line if resolving of metadata fails" in :
    val newSongs = fileIDs(0, 7)
    val helper = new PlaylistControllerTestHelper
    helper.addSongs(newSongs)
      .handleFileInfoRequest(newSongs, Failure(new IllegalStateException("Test exception")))

    verify(helper.statusLineHandler).setText(StatusLineErrorText)

  it should "select newly added songs" in :
    val helper = new PlaylistControllerTestHelper
    helper.addSongs(fileIDs(0, 0))
    verify(helper.tableHandler).setSelectedIndices(Array(0))

    helper.addSongs(fileIDs(0, 2))
    verify(helper.tableHandler).setSelectedIndices(Array(1, 2))

  it should "enable the playlist actions if songs are added" in :
    val helper = new PlaylistControllerTestHelper

    helper.addSongs(fileIDs(0, 0))
      .verifyPlaylistActions(enabled = true)

  it should "execute a playlist manipulator" in :
    val newSongs = fileIDs(0, 1)
    val helper = new PlaylistControllerTestHelper
    helper.addSongs(fileIDs(0, 1))
      .handleSuccessFileInfoRequest(newSongs, fileInfoMap(0, 1))
    val manipulator = new PlaylistManipulator:
      override def updatePlaylist(context: PlaylistSelectionContext): Unit =
        context.tableHandler.getModel.remove(1)

      override def isEnabled(context: PlaylistSelectionContext): Boolean = true

    helper.controller updatePlaylist manipulator
    helper.playlistModel should have size 1
    helper.expectResolvedSongs(0, 0)
    verify(helper.statusLineHandler).setText(generateStatusLine(1, "1:00", 0.5))

  it should "update actions after the playlist has been manipulated" in :
    val helper = new PlaylistControllerTestHelper
    helper addSongs fileIDs(0, 0)
    val manipulator = new PlaylistManipulator:
      override def updatePlaylist(context: PlaylistSelectionContext): Unit =
        context.tableHandler.getModel.clear()

      override def isEnabled(context: PlaylistSelectionContext): Boolean = true

    helper.controller updatePlaylist manipulator
    helper.verifyPlaylistActions(enabled = false)

  /**
    * A test helper class managing dependent objects.
    */
  private class PlaylistControllerTestHelper extends ActionTestHelper with MockitoSugar:
    /** The mock resolver service used by the test controller. */
    private val resolverService = mock[MediaFileInfoResolverService]

    /** The table model. */
    val playlistModel = new util.ArrayList[SongData]

    /** A mock for the table handler. */
    val tableHandler: TableHandler = createTableHandler()

    /** A mock for the status line handler. */
    val statusLineHandler: StaticTextHandler = mock[StaticTextHandler]

    /** The test controller instance. */
    val controller = new PlaylistController(
      resolverService,
      tableHandler,
      statusLineHandler,
      initActions(),
      StatusLineTemplate,
      StatusLineErrorText
    )

    /**
      * Passes a player state change notification to the test controller with
      * a playlist containing the specified songs.
      *
      * @param ids the IDs of the songs in the playlist
      * @return this test helper
      */
    def addSongs(ids: List[String]): PlaylistControllerTestHelper =
      val playlist = Playlist(pendingSongs = ids, playedSongs = Nil)
      val state = AudioPlayerState(
        playlist = playlist,
        playlistSeqNo = 1,
        playbackActive = false,
        playlistClosed = false,
        playlistActivated = true
      )
      controller.receive(state)
      this

    /**
      * Expects an invocation of the resolver service with the given file IDs
      * and invokes the callback function with the given results.
      *
      * @param fileIDs the expected requested file IDs
      * @param results the resulting info objects
      * @return this test helper
      */
    def handleSuccessFileInfoRequest(fileIDs: List[String],
                                     results: Map[String, ArchiveModel.MediaFileInfo]): PlaylistControllerTestHelper =
      handleFileInfoRequest(fileIDs, Success(results))

    /**
      * Expects an invocation of the resolver service with the given file IDs
      * and invokes the callback function with the given tried results.
      *
      * @param fileIDs      the expected requested file IDs
      * @param triedResults the [[Try]] with info objects
      * @return this test helper
      */
    def handleFileInfoRequest(fileIDs: List[String],
                              triedResults: Try[Map[String, ArchiveModel.MediaFileInfo]]):
    PlaylistControllerTestHelper =
      val captCallback = ArgumentCaptor.forClass(classOf[Try[Map[String, ArchiveModel.MediaFileInfo]] => Unit])
      verify(resolverService).resolveFileIDs(argEq(fileIDs))(captCallback.capture())
      captCallback.getValue.apply(triedResults)
      this

    /**
      * Verifies that no file information has been requested from the resolver
      * service.
      *
      * @return this test helper
      */
    def verifyNoFileInfoRequest(): PlaylistControllerTestHelper =
      verifyNoInteractions(resolverService)
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
    private def checkSongsInModel(from: Int, to: Int)(sf: Int => SongData): PlaylistControllerTestHelper =
      (from to to) foreach : i =>
        playlistModel.get(i) should be(sf(i))
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
