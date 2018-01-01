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

package de.oliver_heger.linedj.player.ui

import java.util
import java.util.Collections

import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.player.engine.{AudioSource, PlaybackProgressEvent}
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import net.sf.jguiraffe.gui.builder.components.model.{ProgressBarHandler, StaticTextHandler, TableHandler}
import org.apache.commons.configuration.PropertiesConfiguration
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object CurrentSongControllerSpec {
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

  /** A test SongData object with all relevant properties of the test song. */
  private val TestSongData = SongData(id = MediaFileID(MediumID("foo", None), "uri"),
    metaData = MediaMetaData(inceptionYear = Some(Year), duration = Some(Duration),
      trackNumber = Some(Track)), title = Title, artist = Artist, album = Album)

  /** A SongData object representing an undefined song. */
  private val UndefinedSong = SongData(id = MediaFileID(MediumID("bar", None), "undefined"),
    metaData = MediaMetaData(), title = "", artist = "", album = "")

  /** A test audio source to be used in playback progress events. */
  private val TestSource = AudioSource("testSong", 1000, 0, 0)

  /**
    * Creates a configuration object for the UI application. Some properties
    * relevant for the class under test can be set.
    *
    * @param fieldSize the maximum field size
    * @param speed     the rotation speed
    * @return the application configuration
    */
  private def createUIConfig(fieldSize: Option[Int] = None, speed: Option[Int] = None):
  AudioPlayerConfig = {
    val config = new PropertiesConfiguration
    fieldSize.foreach(s => config.addProperty(AudioPlayerConfig.PropMaxFieldSize, s))
    speed.foreach(s => config.addProperty(AudioPlayerConfig.PropRotationSpeed, s))
    AudioPlayerConfig(config)
  }

  /**
    * Generates a rotated text.
    *
    * @param s  the original text
    * @param by the number of characters to scroll
    * @return the rotated text
    */
  private def rotate(s: String, by: Int): String =
    (s + CurrentSongController.RotationSeparator + s).substring(by, MaxFieldSize + by)
}

/**
  * Test class for ''CurrentSongController''.
  */
class CurrentSongControllerSpec extends FlatSpec with Matchers with MockitoSugar {

  import CurrentSongControllerSpec._

  "A CurrentSongController" should "init the title field" in {
    val helper = new ControllerTestHelper

    helper.triggerPlaylistChanged()
      .verifyTitle()
  }

  it should "init the artist field" in {
    val helper = new ControllerTestHelper

    helper.triggerPlaylistChanged()
      .verifyArtist()
  }

  it should "init the index field" in {
    val helper = new ControllerTestHelper

    helper.triggerPlaylistChanged()
      .verifyIndex(s"${Index + 1} / $PlaylistSize")
  }

  it should "init the album field" in {
    val helper = new ControllerTestHelper

    helper.triggerPlaylistChanged()
      .verifyAlbum(s"$Album ($Track)")
  }

  it should "init the year field" in {
    val helper = new ControllerTestHelper

    helper.triggerPlaylistChanged()
      .verifyYear(Year.toString)
  }

  it should "init the time field" in {
    val helper = new ControllerTestHelper

    helper.triggerPlaylistChanged()
      .verifyDuration(s"0:00 / $DurationFmt")
  }

  it should "init the album field if there is no track number" in {
    val helper = new ControllerTestHelper

    helper.updateCurrentMetaData(_.copy(trackNumber = None))
      .triggerPlaylistChanged()
      .verifyAlbum()
  }

  it should "init the time field if there is no duration" in {
    val helper = new ControllerTestHelper

    helper.updateCurrentMetaData(_.copy(duration = None))
      .triggerPlaylistChanged()
      .verifyDuration("0:00 / ?")
  }

  it should "init the year field if there is no year in meta data" in {
    val helper = new ControllerTestHelper

    helper.updateCurrentMetaData(_.copy(inceptionYear = None))
      .triggerPlaylistChanged()
      .verifyYear("")
  }

  it should "update fields only if there is a change in the current song" in {
    val helper = new ControllerTestHelper

    helper.triggerPlaylistChanged()
      .triggerPlaylistChanged()
      .verifyTitle()
  }

  it should "clear all fields if there is no current song" in {
    val helper = new ControllerTestHelper

    helper.triggerPlaylistChanged()
      .clearTableSelection()
      .triggerPlaylistChanged()
      .verifyTitle("").verifyArtist("").verifyAlbum("")
      .verifyDuration("").verifyYear("").verifyIndex("")
  }

  it should "update the current song on a playlist data change" in {
    val helper = new ControllerTestHelper

    helper.triggerPlaylistDataChanged(Some(Index))
      .verifyTitle()
      .verifyIndex(s"${Index + 1} / $PlaylistSize")
  }

  it should "update the current song on a playlist data change only if needed" in {
    val helper = new ControllerTestHelper

    helper.triggerPlaylistDataChanged(Some(Index))
        .triggerPlaylistDataChanged(Some(Index))
      .verifyTitle()
  }

  it should "clear all fields if the current song on a data change is None" in {
    val helper = new ControllerTestHelper

    helper.triggerPlaylistChanged()
      .triggerPlaylistDataChanged(None)
      .verifyTitle("")
  }

  it should "update the playback time on a progress event" in {
    val helper = new ControllerTestHelper

    helper.triggerPlaylistChanged()
      .sendProgress(posOfs = 0, timeOfs = 63)
      .verifyDuration("1:03 / " + DurationFmt)
  }

  it should "handle a progress event gracefully if there is no current song" in {
    val helper = new ControllerTestHelper

    helper.sendProgress(0, 1)
  }

  it should "reset the map with time update functions if there is no current song" in {
    val helper = new ControllerTestHelper

    helper.triggerPlaylistChanged()
      .clearTableSelection()
      .triggerPlaylistChanged()
      .sendProgress(timeOfs = 0, posOfs = 25)
      .verifyDuration(s"0:00 / $DurationFmt")
  }

  it should "rotate fields that are too long" in {
    val helper = new ControllerTestHelper(config = createUIConfig(fieldSize = Some(MaxFieldSize)))

    helper.triggerPlaylistChanged()
      .sendProgress(timeOfs = 1, posOfs = 0)
      .verifyTitle(rotate(Title, 1))
      .verifyAlbum(rotate(Album, 1))
      .verifyArtist(Artist)
  }

  it should "handle larger offsets when rotating" in {
    val Offset = 12
    val Time = (Title.length + CurrentSongController.RotationSeparator.length) * 5 + Offset
    val helper = new ControllerTestHelper(config = createUIConfig(fieldSize = Some(MaxFieldSize)))

    helper.triggerPlaylistChanged()
      .sendProgress(timeOfs = Time, posOfs = 0)
      .verifyTitle(rotate(Title, Offset))
  }

  it should "handle the rotation speed" in {
    val helper = new ControllerTestHelper(config = createUIConfig(fieldSize = Some(MaxFieldSize),
      speed = Some(2)))

    helper.triggerPlaylistChanged()
      .sendProgress(timeOfs = 3, posOfs = 0)
      .verifyTitle(rotate(Title, 1))
  }

  it should "update the progress bar correctly" in {
    val helper = new ControllerTestHelper

    helper.triggerPlaylistChanged()
      .sendProgress(posOfs = 0, timeOfs = 88)
      .verifyProgress(42)
  }

  it should "reset the position when the current song changes" in {
    val helper = new ControllerTestHelper

    helper.triggerPlaylistChanged()
      .verifyProgress(0)
  }

  it should "handle a song with no duration when calculating progress" in {
    val helper = new ControllerTestHelper

    helper.updateCurrentMetaData(_.copy(duration = None))
      .triggerPlaylistChanged()
      .sendProgress(posOfs = 100, timeOfs = 1)
      .verifyProgress(0)
      .verifyNoMoreProgressUpdates()
  }

  /**
    * Test helper class managing a test instance and its dependencies.
    *
    * @param config the configuration for the UI application
    */
  private class ControllerTestHelper(config: AudioPlayerConfig = createUIConfig()) {
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
      * Notifies the test controller about a change in the playlist.
      *
      * @return this test helper
      */
    def triggerPlaylistChanged(): ControllerTestHelper = {
      controller.playlistStateChanged()
      this
    }

    /**
      * Notifies the test controller about a change in the data of the
      * playlist.
      *
      * @param current the index of the current song
      * @return this test helper
      */
    def triggerPlaylistDataChanged(current: Option[Int]): ControllerTestHelper = {
      controller.playlistDataChanged(current)
      this
    }

    /**
      * Sends a playback progress event to the test controller.
      *
      * @param posOfs  the position offset in the event
      * @param timeOfs the time offset in the event (in seconds)
      * @param source  the source for the event
      * @return this test helper
      */
    def sendProgress(posOfs: Long, timeOfs: Long, source: AudioSource = TestSource):
    ControllerTestHelper = {
      val event = PlaybackProgressEvent(bytesProcessed = posOfs, playbackTime = timeOfs,
        currentSource = source)
      controller playlistProgress event
      this
    }

    /**
      * Allows changing the meta data of the current song in the playlist.
      *
      * @param fMeta a function to update the current meta data
      * @return this test helper
      */
    def updateCurrentMetaData(fMeta: MediaMetaData => MediaMetaData): ControllerTestHelper = {
      val oldSong = tableModel.get(Index).asInstanceOf[SongData]
      tableModel.set(Index, oldSong.copy(metaData = fMeta(oldSong.metaData)))
      this
    }

    /**
      * Prepares the mock for the table controller to report a cleared
      * selection.
      *
      * @return this test helper
      */
    def clearTableSelection(): ControllerTestHelper = {
      when(tableHandler.getSelectedIndex).thenReturn(-1)
      this
    }

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
      * @return this test helper
      */
    def verifyProgress(expValue: Int): ControllerTestHelper = {
      verify(progressHandler).setValue(expValue)
      this
    }

    /**
      * Verifies that there are no more updates on the progress bar element.
      *
      * @return this test helper
      */
    def verifyNoMoreProgressUpdates(): ControllerTestHelper = {
      verifyNoMoreInteractions(progressHandler)
      this
    }

    /**
      * Verifies that a text was correctly set for a text handler.
      *
      * @param handler the text handler
      * @param txt     the expected text
      * @return this test helper
      */
    private def verifyTextHandler(handler: StaticTextHandler, txt: String): ControllerTestHelper = {
      verify(handler).setText(txt)
      this
    }

    /**
      * Creates a test controller instance.
      *
      * @return the test controller
      */
    private def createController(): CurrentSongController =
      new CurrentSongController(tableHandler, config, txtTitle,
        txtArtist, txtAlbum, txtDuration, txtIndex, txtYear, progressHandler)

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
    private def createTableModel(): java.util.List[AnyRef] = {
      val model = new util.ArrayList[AnyRef](PlaylistSize)
      model addAll Collections.nCopies(PlaylistSize, UndefinedSong)
      model.set(Index, TestSongData)
      model
    }

    /**
      * Creates a mock for the table handler. The mock is already configured
      * with some basic behavior.
      *
      * @return the mock for the table handler
      */
    private def createTableHandler(): TableHandler = {
      val handler = mock[TableHandler]
      when(handler.getModel).thenReturn(tableModel)
      when(handler.getSelectedIndex).thenReturn(Index)
      handler
    }
  }

}
