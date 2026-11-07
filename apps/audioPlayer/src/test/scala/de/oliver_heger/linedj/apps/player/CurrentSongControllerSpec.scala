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

package de.oliver_heger.linedj.apps.player

import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.platform.audio2.PlaybackProgress
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import net.sf.jguiraffe.gui.builder.components.model.{ProgressBarHandler, StaticTextHandler, TableHandler}
import org.apache.commons.configuration.PropertiesConfiguration
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util
import java.util.Collections
import scala.concurrent.duration.*

object CurrentSongControllerSpec:
  /** Title of the test song. */
  private val Title = "Scenes From A Night's Dream"

  /** Artist of the test song. */
  private val Artist = "Genesis"

  /** Album of the test song. */
  private val Album = "... And Then There Were Three"

  /** Track number of the test song. */
  private val Track = 8

  /** Index of the test song in the total playlist. */
  private val Index = 4

  /** Duration of the test song in milliseconds. */
  private val Duration = 209000

  /** Formatted duration of the test song. */
  private val DurationFmt = "3:29"

  /** Inception year of the test song. */
  private val Year = 1978

  /** The number of songs in the playlist. */
  private val PlaylistSize = 8

  /** Maximum field size used by some tests. */
  private val MaxFieldSize = 16

  /** A test file info object with all relevant properties of the test song. */
  private val TestFileInfo = ArchiveModel.MediaFileInfo(
    fileUri = MediaFileUri("testMediaFile"),
    mediumID = Checksums.MediumChecksum("someMedium"),
    metadata = MediaMetadata(
      title = Some(Title),
      artist = Some(Artist),
      album = Some(Album),
      inceptionYear = Some(Year),
      duration = Some(Duration),
      trackNumber = Some(Track),
      size = 42,
      checksum = "check"
    )
  )

  /** A test song data object containing the test file info. */
  private val TestSongData = SongData(TestFileInfo)

  /** A file info object representing a song with undefined properties. */
  private val UndefinedSong = ArchiveModel.MediaFileInfo(
    fileUri = MediaFileUri("undefined"),
    mediumID = Checksums.MediumChecksum("undefinedMedium"),
    metadata = MediaMetadata.UndefinedMediaData
  )

  /** A state object that references the test song. */
  private val StateWithDefinedSong = createUiState(Some(TestSongData))

  /**
    * Creates an [[AudioPlayerUIState]] object that references the provided
    * current song and has dummy values for other fields.
    *
    * @param optCurrent the optional current song
    * @return the UI state object
    */
  private def createUiState(optCurrent: Option[SongData]): AudioPlayerUIState =
    AudioPlayerUIState(
      playlist = PlaylistTestHelper.songDataList(1, PlaylistSize)(),
      playlistSeqNo = 0,
      optCurrentIndex = Some(Index),
      idsToResolve = Nil,
      pendingResolveCnt = 0,
      optCurrentSong = optCurrent
    )

  /**
    * Allows changing the metadata of the defined test song using a specified
    * modification function.
    *
    * @param fMeta a function to update the metadata
    * @return this test helper
    */
  private def updateCurrentMetaData(fMeta: MediaMetadata => MediaMetadata): AudioPlayerUIState =
    val updatedFileInfo = TestSongData.fileInfo.copy(metadata = fMeta(TestSongData.fileInfo.metadata))
    val newSongData = SongData(updatedFileInfo)
    createUiState(Some(newSongData))

  /**
    * Creates a configuration object for the UI application. Some properties
    * relevant for the class under test can be set.
    *
    * @param fieldSize the maximum field size
    * @param speed     the rotation speed
    * @return the application configuration
    */
  private def createUIConfig(fieldSize: Option[Int] = None, speed: Option[Int] = None): AudioPlayerConfig =
    val config = new PropertiesConfiguration
    fieldSize.foreach(s => config.addProperty(AudioPlayerConfig.PropMaxFieldSize, s))
    speed.foreach(s => config.addProperty(AudioPlayerConfig.PropRotationSpeed, s))
    AudioPlayerConfig(config)

  /**
    * Generates a rotated text.
    *
    * @param s  the original text
    * @param by the number of characters to scroll
    * @return the rotated text
    */
  private def rotate(s: String, by: Int): String =
    (s + " * " + s).substring(by, MaxFieldSize + by)
end CurrentSongControllerSpec

/**
  * Test class for [[CurrentSongController]].
  */
class CurrentSongControllerSpec extends AnyFlatSpec, Matchers, MockitoSugar:

  import CurrentSongControllerSpec.*

  "A CurrentSongController" should "init the title field" in :
    val helper = new ControllerTestHelper

    helper.triggerStateChange(StateWithDefinedSong)
      .verifyTitle()

  it should "init the artist field" in :
    val helper = new ControllerTestHelper

    helper.triggerStateChange(StateWithDefinedSong)
      .verifyArtist()

  it should "init the index field" in :
    val helper = new ControllerTestHelper

    helper.triggerStateChange(StateWithDefinedSong)
      .verifyIndex(s"${Index + 1} / $PlaylistSize")

  it should "init the album field" in :
    val helper = new ControllerTestHelper

    helper.triggerStateChange(StateWithDefinedSong)
      .verifyAlbum(s"$Album ($Track)")

  it should "init the year field" in :
    val helper = new ControllerTestHelper

    helper.triggerStateChange(StateWithDefinedSong)
      .verifyYear(Year.toString)

  it should "init the time field" in :
    val helper = new ControllerTestHelper

    helper.triggerStateChange(StateWithDefinedSong)
      .verifyDuration(s"0:00 / $DurationFmt")

  it should "init the album field if there is no track number" in :
    val state = updateCurrentMetaData(_.copy(trackNumber = None))
    val helper = new ControllerTestHelper

    helper.triggerStateChange(state)
      .verifyAlbum(Album + " (-)")

  it should "init the time field if there is no duration" in :
    val state = updateCurrentMetaData(_.copy(duration = None))
    val helper = new ControllerTestHelper

    helper.triggerStateChange(state)
      .verifyDuration("0:00 / ?")

  it should "init the year field if there is no year in metadata" in :
    val state = updateCurrentMetaData(_.copy(inceptionYear = None))
    val helper = new ControllerTestHelper

    helper.triggerStateChange(state)
      .verifyYear("-")

  it should "update fields only if there is a change in the current song" in :
    val nextState = StateWithDefinedSong.copy(
      playlistSeqNo = 17,
      idsToResolve = List("some-song"),
      pendingResolveCnt = 2
    )
    val helper = new ControllerTestHelper

    helper.triggerStateChange(StateWithDefinedSong)
      .triggerStateChange(nextState)
      .verifyTitle() // Only one update.

  it should "clear all fields if there is no current song" in :
    val helper = new ControllerTestHelper

    helper.triggerStateChange(createUiState(None))
      .verifyTitle("")
      .verifyArtist("")
      .verifyAlbum("")
      .verifyDuration("")
      .verifyYear("")
      .verifyIndex("")

  it should "update properties if there is a change in the current index" in :
    val nextState = StateWithDefinedSong.copy(optCurrentIndex = Some(Index + 1))
    val helper = new ControllerTestHelper

    helper.triggerStateChange(StateWithDefinedSong)
      .triggerStateChange(nextState)
      .verifyIndex(s"${Index + 2} / $PlaylistSize")

  it should "update properties if there is a change in the playlist" in :
    val nextState = StateWithDefinedSong.copy(playlist = PlaylistTestHelper.songDataList(1, PlaylistSize + 1)())
    val helper = new ControllerTestHelper

    helper.triggerStateChange(StateWithDefinedSong)
      .triggerStateChange(nextState)
      .verifyIndex(s"${Index + 1} / ${PlaylistSize + 1}")


  it should "update the playback time on a progress event" in :
    val helper = new ControllerTestHelper

    helper.triggerStateChange(StateWithDefinedSong)
      .sendProgress(posOfs = 0, timeOfs = 63)
      .verifyDuration("1:03 / " + DurationFmt)

  it should "handle a progress event gracefully if there is no current song" in :
    val helper = new ControllerTestHelper

    helper.sendProgress(0, 1)

  it should "rotate fields that are too long" in :
    val helper = new ControllerTestHelper(config = createUIConfig(fieldSize = Some(MaxFieldSize)))

    helper.triggerStateChange(StateWithDefinedSong)
      .sendProgress(timeOfs = 1, posOfs = 0)
      .verifyTitle(rotate(Title, 1))
      .verifyAlbum(rotate(Album, 1))
      .verifyArtist(Artist)

  it should "handle larger offsets when rotating" in :
    val Offset = 12
    val Time = (Title.length + 3) * 5 + Offset
    val helper = new ControllerTestHelper(config = createUIConfig(fieldSize = Some(MaxFieldSize)))

    helper.triggerStateChange(StateWithDefinedSong)
      .sendProgress(timeOfs = Time, posOfs = 0)
      .verifyTitle(rotate(Title, Offset))

  it should "handle the rotation speed" in :
    val helper = new ControllerTestHelper(config = createUIConfig(fieldSize = Some(MaxFieldSize), speed = Some(2)))

    helper.triggerStateChange(StateWithDefinedSong)
      .sendProgress(timeOfs = 2, posOfs = 0)
      .verifyTitle(rotate(Title, 4))

  it should "update the progress bar correctly" in :
    val helper = new ControllerTestHelper

    helper.triggerStateChange(StateWithDefinedSong)
      .sendProgress(posOfs = 0, timeOfs = 88)
      .verifyProgress(42)

  it should "reset the position when the current song changes" in :
    val nextState = StateWithDefinedSong.copy(optCurrentIndex = Some(Index + 1))
    val helper = new ControllerTestHelper

    helper.triggerStateChange(StateWithDefinedSong)
      .triggerStateChange(nextState)
      .verifyProgress(0, expCount = 2)

  it should "handle a song with no duration when calculating progress" in :
    val state = updateCurrentMetaData(_.copy(duration = None))
    val helper = new ControllerTestHelper

    helper.triggerStateChange(state)
      .sendProgress(posOfs = 100, timeOfs = 1)
      .verifyProgress(0)
      .verifyNoMoreProgressUpdates()

  it should "reset the progress bar if there is no current song" in :
    val helper = new ControllerTestHelper

    helper.triggerStateChange(StateWithDefinedSong)
      .sendProgress(posOfs = 0, timeOfs = 100)
      .triggerStateChange(createUiState(None))
      .verifyProgress(0, expCount = 2)

  /**
    * Test helper class managing a test instance and its dependencies.
    *
    * @param config the configuration for the UI application
    */
  private class ControllerTestHelper(config: AudioPlayerConfig = createUIConfig()):
    /** The collection serving as table model. */
    private val tableModel = createTableModel()

    /** Mock for the table handler. */
    private val tableHandler = createTableHandler()

    /** Text handler for the title property. */
    private val txtTitle = createTextHandler()

    /** Text handler for the artist property. */
    private val txtArtist = createTextHandler()

    /** Text handler for the album property. */
    private val txtAlbum = createTextHandler()

    /** Text handler for the duration property. */
    private val txtDuration = createTextHandler()

    /** Text handler for the Index property. */
    private val txtIndex = createTextHandler()

    /** Text handler for the year property. */
    private val txtYear = createTextHandler()

    /** The mock for the progress bar handler. */
    private val progressHandler = mock[ProgressBarHandler]

    /** The controller to be tested. */
    private val controller = createController()

    /**
      * Notifies the test controller about a change in the UI state.
      *
      * @param state the new UI state
      * @return this test helper
      */
    def triggerStateChange(state: AudioPlayerUIState): ControllerTestHelper =
      controller.handleStateUpdate(state)
      this

    /**
      * Sends a playback progress event to the test controller.
      *
      * @param posOfs  the position offset in the event
      * @param timeOfs the time offset in the event (in seconds)
      * @return this test helper
      */
    def sendProgress(posOfs: Long, timeOfs: Long): ControllerTestHelper =
      val progress = PlaybackProgress(bytesProcessed = posOfs, playbackTime = timeOfs.seconds)
      controller.playlistProgress(progress)
      this

    /**
      * Allows changing the metadata of the current song in the playlist.
      *
      * @param fMeta a function to update the current metadata
      * @return this test helper
      */
    def updateCurrentMetaData(fMeta: MediaMetadata => MediaMetadata): ControllerTestHelper =
      val oldSong = tableModel.get(Index).asInstanceOf[SongData]
      val updatedFileInfo = oldSong.fileInfo.copy(metadata = fMeta(oldSong.fileInfo.metadata))
      tableModel.set(Index, SongData(updatedFileInfo))
      this

    /**
      * Verifies that the correct title text was set.
      *
      * @param title the expected text
      * @return this text helper
      */
    def verifyTitle(title: String = Title): ControllerTestHelper =
      verifyTextHandler(txtTitle, title)

    /**
      * Verifies that the correct artist text was set.
      *
      * @param artist the expected text
      * @return this text helper
      */
    def verifyArtist(artist: String = Artist): ControllerTestHelper =
      verifyTextHandler(txtArtist, artist)

    /**
      * Verifies that the correct album text was set.
      *
      * @param album the expected text
      * @return this text helper
      */
    def verifyAlbum(album: String = Album): ControllerTestHelper =
      verifyTextHandler(txtAlbum, album)

    /**
      * Verifies that the correct index text was set.
      *
      * @param index the expected text
      * @return this text helper
      */
    def verifyIndex(index: String): ControllerTestHelper =
      verifyTextHandler(txtIndex, index)

    /**
      * Verifies that the correct duration text was set.
      *
      * @param duration the expected text
      * @return this text helper
      */
    def verifyDuration(duration: String): ControllerTestHelper =
      verifyTextHandler(txtDuration, duration)

    /**
      * Verifies that the correct year text was set.
      *
      * @param year the expected text
      * @return this text helper
      */
    def verifyYear(year: String = Year.toString): ControllerTestHelper =
      verifyTextHandler(txtYear, year)

    /**
      * Verifies that the progress bar has been updated correctly.
      *
      * @param expValue the expected value
      * @param expCount the number of times the value is expected
      * @return this test helper
      */
    def verifyProgress(expValue: Int, expCount: Int = 1): ControllerTestHelper =
      verify(progressHandler, times(expCount)).setValue(expValue)
      this

    /**
      * Verifies that there are no more updates on the progress bar element.
      *
      * @return this test helper
      */
    def verifyNoMoreProgressUpdates(): ControllerTestHelper =
      verifyNoMoreInteractions(progressHandler)
      this

    /**
      * Verifies that a text was correctly set for a text handler.
      *
      * @param handler the text handler
      * @param txt     the expected text
      * @return this test helper
      */
    private def verifyTextHandler(handler: StaticTextHandler, txt: String): ControllerTestHelper =
      verify(handler).setText(txt)
      this

    /**
      * Creates a test controller instance.
      *
      * @return the test controller
      */
    private def createController(): CurrentSongController =
      new CurrentSongController(
        tableHandler,
        config,
        txtTitle,
        txtArtist,
        txtAlbum,
        txtDuration,
        txtIndex,
        txtYear,
        progressHandler
      )

    /**
      * Creates a mock for a text handler.
      *
      * @return the mock text handler
      */
    private def createTextHandler(): StaticTextHandler = mock[StaticTextHandler]

    /**
      * Creates the collection for the table model. The collection contains a
      * number of dummy songs and the test song at the correct position.
      *
      * @return the initialized table model collection
      */
    private def createTableModel(): java.util.List[AnyRef] =
      val model = new util.ArrayList[AnyRef](PlaylistSize)
      model addAll Collections.nCopies(PlaylistSize, UndefinedSong)
      model.set(Index, TestSongData)
      model

    /**
      * Creates a mock for the table handler. The mock is already configured
      * with some basic behavior.
      *
      * @return the mock for the table handler
      */
    private def createTableHandler(): TableHandler =
      val handler = mock[TableHandler]
      when(handler.getModel).thenReturn(tableModel)
      when(handler.getSelectedIndex).thenReturn(Index)
      handler
