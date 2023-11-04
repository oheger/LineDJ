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

package de.oliver_heger.linedj.player.ui

import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.platform.ui.TextTimeFunctions.TextTimeFunc
import de.oliver_heger.linedj.platform.ui.{DurationTransformer, TextTimeFunctions}
import de.oliver_heger.linedj.player.engine.PlaybackProgressEvent
import net.sf.jguiraffe.gui.builder.components.model.{ProgressBarHandler, StaticTextHandler, TableHandler}

object CurrentSongController:
  /** Constant for an empty/undefined field. */
  private val Empty = ""

  /** Constant for an unknown duration. */
  private val UnknownDuration = "?"

  /**
    * A function to calculate a single value to be assigned to a field in the
    * details pane when the current song in the playlist changes.
    */
  private type PlaylistUpdateFunc = CurrentSongData => String

  /**
    * Generates the text to be displayed for the album. Here also the track
    * number is embedded if it is defined.
    *
    * @param data the current song data
    * @return the text for the album field
    */
  private def generateAlbumValue(data: CurrentSongData): String =
    val track = data.song.metaData.trackNumber.map(t => s" ($t)").getOrElse(Empty)
    s"${data.song.album}$track"

  /**
    * Generates a map with the texts to be written into the details fields.
    *
    * @param funcs the map with the update functions
    * @param data  data about the current song
    * @return a map with handlers and the text to be assigned to them
    */
  private def fieldValues(funcs: Map[StaticTextHandler, PlaylistUpdateFunc],
                          data: CurrentSongData): Map[StaticTextHandler, String] =
    funcs map (e => (e._1, e._2(data)))

  /**
    * Internally used data class with all information about the current song in
    * the playlist.
    *
    * @param index        the index in the playlist
    * @param playlistSize the size of the playlist
    * @param song         the current song
    * @param sDuration    the formatted duration
    */
  private case class CurrentSongData(index: Int, playlistSize: Int, song: SongData,
                                     sDuration: String)


/**
  * A class responsible for managing UI controls that display detail
  * information about the currently played song.
  *
  * The UI of the audio player application mainly consists of a table with the
  * songs in the playlist and a panel showing detail information about the
  * current song. This controller is responsible for the latter. Whenever there
  * is a change in the current song (or its data), the text elements in the
  * details panel have to be updated accordingly. To keep track on the playback
  * time, an instance is also informed about playback progress events.
  *
  * Sometimes the titles of songs or albums are pretty long and cannot be
  * displayed fully in the UI. To handle this, a ''rotation mode'' is
  * supported. If the length of a property exceeds a configurable length, it is
  * rotated, i.e. it scrolls through its value. Scrolling depends on incoming
  * playback events and some configuration settings. Basically, the playback
  * time is divided by a configurable scroll speed. The result is then used as
  * offset of the first character of the property to be displayed.
  *
  * @param tableHandler    the handler for the playlist table
  * @param config          the application's configuration
  * @param txtTitle        text control for the tile
  * @param txtArtist       text control for the artist
  * @param txtAlbum        text control for the album
  * @param txtTime         text control for the playback time
  * @param txtIndex        text control for the index in the playlist
  * @param txtYear         text control for the year
  * @param progressHandler the playback progress bar
  */
class CurrentSongController(tableHandler: TableHandler, config: AudioPlayerConfig,
                            txtTitle: StaticTextHandler, txtArtist: StaticTextHandler,
                            txtAlbum: StaticTextHandler, txtTime: StaticTextHandler,
                            txtIndex: StaticTextHandler, txtYear: StaticTextHandler,
                            progressHandler: ProgressBarHandler):

  import CurrentSongController._

  /**
    * The functions to be executed when there is a change in the current song.
    */
  private val playlistUpdateFunctions = createPlaylistUpdateFunctions()

  /**
    * The functions to be executed when a playback progress event arrives
    * indicating a change in the playback time.
    */
  private var timeUpdateFunctions = Map.empty[StaticTextHandler, TextTimeFunc]

  /**
    * Stores the latest current song. This is used to find out whether there is
    * a change that needs to be reflected in the UI.
    */
  private var currentSong: Option[CurrentSongData] = None

  /**
    * Notifies this controller that there was a change in the state of the
    * playlist. If necessary, the UI controls in the details panel are
    * updated.
    */
  def playlistStateChanged(): Unit =
    handlePlaylistChange(fetchCurrentSong())

  /**
    * Notifies this controller that there was a change in the data of the
    * playlist. (Probably new meta data arrived.) If the current song is
    * affected, the UI has to be updated.
    *
    * @param current an ''Option'' with the index of the current song in the
    *                playlist
    */
  def playlistDataChanged(current: Option[Int]): Unit =
    handlePlaylistChange(current map (c => createCurrentSongData(c)))

  /**
    * Notifies this controller about a progress in the playback of the current
    * song. Some of the fields managed by this controller depend on the current
    * playback position and time. These have to be updated.
    *
    * @param event the playback progress event
    */
  def playlistProgress(event: PlaybackProgressEvent): Unit =
    timeUpdateFunctions foreach (e => e._1 setText e._2(event.playbackTime))
    calcPlaybackProgress(event) foreach updateProgress

  /**
    * Handles a change of the playlist. If the change affects the current song,
    * the UI is updated accordingly.
    *
    * @param optData an ''Option'' with information about the current song
    */
  private def handlePlaylistChange(optData: Option[CurrentSongData]): Unit =
    if optData != currentSong then
      optData match
        case Some(data) =>
          updateDataForCurrentSong(data)
        case None =>
          clearAllFields()
      currentSong = optData

  /**
    * Updates the UI and some internal fields after a change of the current
    * song in the playlist.
    *
    * @param data data about the new current song
    */
  private def updateDataForCurrentSong(data: CurrentSongData): Unit =
    val values = fieldValues(playlistUpdateFunctions, data)
    values foreach (e => e._1 setText e._2)
    updateProgress(0)
    timeUpdateFunctions = createTimeUpdateFunctions(data, config.maxUIFieldSize, config.rotationSpeed, values)

  /**
    * Generates a map with update functions for fields based on the playback
    * time. The map always contains the field for the playback time itself;
    * in addition, rotation functions for longer fields are added.
    *
    * @param data   data about the current song
    * @param maxLen the maximum field length
    * @param speed  the rotation speed
    * @param texts  the map with current texts
    * @return the map with time update functions
    */
  private def createTimeUpdateFunctions(data: CurrentSongData, maxLen: Int, speed: Double,
                                        texts: Map[StaticTextHandler, String]):
  Map[StaticTextHandler, TextTimeFunc] =
    val rotates = texts.filter(e => e._2.length >= maxLen)
      .foldLeft(Map.empty[StaticTextHandler, TextTimeFunc]) { (m, e) =>
        m + (e._1 -> TextTimeFunctions.rotateText(e._2, maxLen, scale = speed))
      }
    rotates + (txtTime -> TextTimeFunctions.withSuffix(" / " + data.sDuration)(TextTimeFunctions.formattedTime()))

  /**
    * Clears all fields in the details pane. This method is called if there is
    * no current song in the playlist for which details could be displayed.
    */
  private def clearAllFields(): Unit =
    playlistUpdateFunctions.keys foreach (_.setText(Empty))
    timeUpdateFunctions = Map.empty
    updateProgress(0)

  /**
    * Calculates the playback progress for the current song based on the
    * passed in progress event.
    *
    * @param event the event
    * @return an ''Option'' with the playback progress
    */
  private def calcPlaybackProgress(event: PlaybackProgressEvent): Option[Int] = for
    s <- currentSong
    d <- s.song.metaData.duration
  yield scala.math.round(100 * event.playbackTime.toMillis.toFloat / d)

  /**
    * Updates the progress bar control.
    *
    * @param v the value to be set
    */
  private def updateProgress(v: Int): Unit =
    progressHandler setValue v

  /**
    * Returns an ''Option'' with information about the current song. The
    * information is obtained from the table handler, based on its current
    * selection. If there is no selection, result is ''None''.
    *
    * @return an ''Option'' with data about the current song
    */
  private def fetchCurrentSong(): Option[CurrentSongData] =
    val index = tableHandler.getSelectedIndex
    if index < 0 then None
    else Some(createCurrentSongData(index))

  /**
    * Creates a ''CurrentSongData'' object from the given input.
    *
    * @param index the index in the playlist
    * @return the ''CurrentSongData''
    */
  private def createCurrentSongData(index: Int): CurrentSongData =
    val songData = tableHandler.getModel.get(index).asInstanceOf[SongData]
    CurrentSongData(index + 1, tableHandler.getModel.size(),
      songData, extractDuration(songData))

  /**
    * Extracts the (formatted) duration of the specified song. If the duration
    * is undefined, a default string is returned.
    *
    * @param songData the current song
    * @return the formatted duration
    */
  private def extractDuration(songData: SongData): String =
    songData.metaData.duration.map(d => DurationTransformer.formatDuration(d))
      .getOrElse(UnknownDuration)

  /**
    * Generates the map with functions to be invoked when there is a change
    * in the current song of the playlist.
    *
    * @return the map with playlist update functions
    */
  private def createPlaylistUpdateFunctions(): Map[StaticTextHandler, PlaylistUpdateFunc] =
    Map(txtTitle -> (_.song.title),
      txtArtist -> (_.song.artist),
      txtAlbum -> generateAlbumValue,
      txtIndex -> (data => s"${data.index} / ${data.playlistSize}"),
      txtYear -> (_.song.metaData.inceptionYear.map(_.toString) getOrElse Empty),
      txtTime -> (DurationTransformer.formatDuration(0) + " / " + _.sDuration))
