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
import de.oliver_heger.linedj.platform.ui.TextTimeFunctions.TextTimeFunc
import de.oliver_heger.linedj.platform.ui.{DurationTransformer, TextTimeFunctions}
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import net.sf.jguiraffe.gui.builder.components.model.{ProgressBarHandler, StaticTextHandler, TableHandler}

object CurrentSongController:
  /** Constant for an empty/undefined field. */
  private val Empty = ""

  /** Constant for an unknown duration. */
  private val UnknownDuration = "?"

  /**
    * Constant for a song data object with all properties undefined. This is
    * used to populate UI fields if no current song is available.
    */
  private val UndefinedSong = SongData(
    ArchiveModel.MediaFileInfo(
      fileUri = MediaFileUri(""),
      mediumID = Checksums.MediumChecksum(""),
      metadata = MediaMetadata(size = 0, checksum = "")
    )
  )

  /**
    * A function to calculate a single value to be assigned to a field in the
    * details pane when the current song in the playlist changes.
    */
  private type PlaylistUpdateFunc = CurrentSongData => String

  /**
    * Generates the text to be displayed for the album. Here also the track
    * number is embedded.
    *
    * @param data the current song data
    * @return the text for the album field
    */
  private def generateAlbumValue(data: CurrentSongData): String =
    s"${data.song.getAlbum} (${data.song.getTrackNumber})"

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
    * @param optIndex     the index in the playlist if available
    * @param playlistSize the size of the playlist
    * @param optSong      information about the current song if available
    * @param sDuration    the formatted duration
    */
  private case class CurrentSongData(optIndex: Option[Int],
                                     playlistSize: Int,
                                     optSong: Option[SongData],
                                     sDuration: String):
    /**
      * Returns the index of the current song or a negative value if there is
      * none.
      *
      * @return the index of the current song
      */
    def index: Int = optIndex.map(_ + 1).getOrElse(-1)

    /**
      * Returns a data object for the current song. If there is none, a special
      * object with only undefined properties is returned.
      *
      * @return a data object for the current song
      */
    def song: SongData = optSong.getOrElse(UndefinedSong)
end CurrentSongController

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
  * displayed fully in the UI. To handle this, a _rotation mode_ is
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
class CurrentSongController(tableHandler: TableHandler,
                            config: AudioPlayerConfig,
                            txtTitle: StaticTextHandler,
                            txtArtist: StaticTextHandler,
                            txtAlbum: StaticTextHandler,
                            txtTime: StaticTextHandler,
                            txtIndex: StaticTextHandler,
                            txtYear: StaticTextHandler,
                            progressHandler: ProgressBarHandler):

  import CurrentSongController.*

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
  private var currentSong = CurrentSongData(
    optIndex = None,
    optSong = None,
    playlistSize = 0,
    sDuration = ""
  )

  /**
    * Notifies this object about a change in the state of the audio player.
    * If necessary, the labels for the current song are updated.
    *
    * @param state the updated state
    */
  def handleStateUpdate(state: AudioPlayerUIState): Unit =
    if updateRequired(state) then
      val nextCurrentSong = createCurrentSongData(state)
      if nextCurrentSong.optSong.isDefined then
        updateUIUForCurrentSong(nextCurrentSong)
      else
        clearAllFields()
      currentSong = nextCurrentSong

  /**
    * Notifies this controller about progress in the playback of the current
    * song. Some of the fields managed by this controller depend on the current
    * playback position and time. These have to be updated.
    *
    * @param progress the playback progress event
    */
  def playlistProgress(progress: PlaybackProgress): Unit =
    timeUpdateFunctions foreach (e => e._1.setText(e._2(progress.playbackTime)))
    calcPlaybackProgress(progress) foreach updateProgress

  /**
    * Updates the UI and some internal fields after a change of the current
    * song in the playlist.
    *
    * @param data data about the new current song
    */
  private def updateUIUForCurrentSong(data: CurrentSongData): Unit =
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
                                        texts: Map[StaticTextHandler, String]): Map[StaticTextHandler, TextTimeFunc] =
    val rotates = texts.filter(e => e._2.length >= maxLen)
      .foldLeft(Map.empty[StaticTextHandler, TextTimeFunc]): (m, e) =>
        m + (e._1 -> TextTimeFunctions.rotateText(e._2, maxLen, scale = speed))
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
    * @param progress the object with progress information
    * @return an [[Option]] with the playback progress
    */
  private def calcPlaybackProgress(progress: PlaybackProgress): Option[Int] = for
    s <- currentSong.optSong
    d <- s.fileInfo.metadata.duration
  yield scala.math.round(100 * progress.playbackTime.toMillis.toFloat / d)

  /**
    * Updates the progress bar control.
    *
    * @param v the value to be set
    */
  private def updateProgress(v: Int): Unit =
    progressHandler.setValue(v)

  /**
    * Checks whether the given state has changed in a way that requires an
    * update of the UI.
    *
    * @param state the updated state
    * @return a flag whether the UI needs to be updated
    */
  private def updateRequired(state: AudioPlayerUIState): Boolean =
    state.optCurrentSong != currentSong.optSong || state.optCurrentIndex != currentSong.optIndex ||
      state.playlist.size != currentSong.playlistSize

  /**
    * Creates a [[CurrentSongData]] object from the given state object.
    *
    * @param state the current state of the player UID
    * @return the [[CurrentSongData]]
    */
  private def createCurrentSongData(state: AudioPlayerUIState): CurrentSongData =
    CurrentSongData(
      optIndex = state.optCurrentIndex,
      optSong = state.optCurrentSong,
      playlistSize = state.playlist.size,
      sDuration = extractDuration(state.optCurrentSong)
    )

  /**
    * Extracts the (formatted) duration of the specified song. If the duration
    * is undefined, a default string is returned.
    *
    * @param songData the current song
    * @return the formatted duration
    */
  private def extractDuration(songData: Option[SongData]): String =
    songData.flatMap(_.fileInfo.metadata.duration)
      .map(d => DurationTransformer.formatDuration(d))
      .getOrElse(UnknownDuration)

  /**
    * Generates the map with functions to be invoked when there is a change
    * in the current song of the playlist.
    *
    * @return the map with playlist update functions
    */
  private def createPlaylistUpdateFunctions(): Map[StaticTextHandler, PlaylistUpdateFunc] =
    Map(
      txtTitle -> (_.song.getTitle),
      txtArtist -> (_.song.getArtist),
      txtAlbum -> generateAlbumValue,
      txtIndex -> (data => s"${data.index} / ${data.playlistSize}"),
      txtYear -> (_.song.getInceptionYear),
      txtTime -> (DurationTransformer.formatDuration(0) + " / " + _.sDuration)
    )
