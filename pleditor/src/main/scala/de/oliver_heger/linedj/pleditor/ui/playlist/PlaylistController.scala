/*
 * Copyright 2015-2022 The Developers Team.
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

import de.oliver_heger.linedj.platform.audio.model.{SongData, SongDataFactory}
import de.oliver_heger.linedj.platform.audio.playlist.{PlaylistMetaData, PlaylistMetaDataRegistration}
import de.oliver_heger.linedj.platform.audio.{AudioPlayerStateChangeRegistration, AudioPlayerStateChangedEvent}
import de.oliver_heger.linedj.platform.bus.{ConsumerSupport, Identifiable}
import de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension.ConsumerRegistrationProvider
import de.oliver_heger.linedj.platform.ui.DurationTransformer
import de.oliver_heger.linedj.shared.archive.media.MediaFileID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.model.{StaticTextHandler, TableHandler}

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._

object PlaylistController {
  /** Factor for the size of a Megabyte. */
  private val Megabyte = 1024 * 1024.0

  /** A string serving as indication that there are songs with unknown duration. */
  private val IndicationUnknownDuration = "> "

  /** The name of the export action. */
  private val ActionExport = "plExportAction"

  /** The name of the activate action. */
  private val ActionActivate = "plActivateAction"

  /**
    * A list with actions whose enabled state has to be managed by the
    * controller.
    */
  private val ManagedActions = List(ActionExport, ActionActivate)

  /** Constant for undefined meta data. */
  private[playlist] val UndefinedMetaData = MediaMetaData()

  /**
    * Checks whether the meta data of a song is unresolved.
    *
    * @param song the song to be checked
    * @return a flag whether this song is not yet resolved
    */
  private def isSongUnresolved(song: SongData): Boolean =
    song.metaData eq UndefinedMetaData

  /**
   * Generates the text to be displayed in the status line based on the
   * specified template.
   *
   * @param songs the current list with songs in the playlist
   * @param template the template for generating the status line
   * @return the text for the status line
   */
  private def generateStatusLineText(songs: Seq[Any], template: String): String = {
    val songData = songs map (_.asInstanceOf[SongData])
    val (size, duration) = songData.foldLeft((0L, 0)) { (d, s) =>
      (d._1 + s.metaData.size, d._2 + s.getDuration)
    }
    val durationDefined = songData forall (_.getDuration > 0)
    val totalDuration = DurationTransformer.formatDuration(duration)
    val actDuration = if (durationDefined) totalDuration
    else IndicationUnknownDuration + totalDuration
    template.format(songs.size, actDuration, size / Megabyte)
  }
}

/**
 * The controller class for the ''playlist'' tab.
 *
 * The playlist tab allows defining a playlist. It displays all songs that have
 * been added to the current playlist (which is done from other tabs). The list
 * can then be manipulated, e.g. reordered.
 *
 * @param tableHandler the handler for the playlist table widget
 * @param statusLine the widget representing the status line
 * @param actionStore the current ''ActionStore''
 * @param statusLineTemplate a localized template for generating the text for
 *                           the status line; it has to contain 3 %
 *                           placeholders for the number of songs, the
 *                           duration, and the size in MB
 * @param songDataFactory the factory for song objects
 */
class PlaylistController(tableHandler: TableHandler, statusLine: StaticTextHandler,
                         actionStore: ActionStore, statusLineTemplate: String,
                         songDataFactory: SongDataFactory) extends
  ConsumerRegistrationProvider with Identifiable {
  import PlaylistController._

  /** The map with meta data currently available. */
  private var metaData = Map.empty[MediaFileID, MediaMetaData]

  /**
    * The number of songs with unresolved meta data. This is used as an
    * optimization to avoid unnecessary traversal of the table model when new
    * meta data arrives. The value is reset when the playlist is manipulated.
    */
  private var unresolvedSongCount = 0

  /**
    * The consumer registrations used by this controller. The controller needs
    * notifications about player state change events and incoming playlist meta
    * data.
    */
  override val registrations: Iterable[ConsumerSupport.ConsumerRegistration[_]] =
    List(AudioPlayerStateChangeRegistration(componentID, handleAudioPlayerStateChangeEvent),
      PlaylistMetaDataRegistration(componentID, handlePlaylistMetaData))

  /**
    * Updates the current playlist by invoking the specified
    * ''PlaylistManipulator''.
    *
    * @param manipulator the ''PlaylistManipulator''
    */
  def updatePlaylist(manipulator: PlaylistManipulator): Unit = {
    manipulator updatePlaylist PlaylistSelectionContext(tableHandler)
    updateStatusLine()
    updateActions()
    unresolvedSongCount = tableHandler.getModel.size()
  }

  /**
    * Consumer function for state change events of the audio player. If new
    * songs have been added to the playlist, they are also added to managed
    * table.
    *
    * @param event the change event
    */
  private def handleAudioPlayerStateChangeEvent(event: AudioPlayerStateChangedEvent): Unit = {
    val songs = event.state.playlist.pendingSongs
    val currentTableSize = tableHandler.getModel.size()
    if (songs.lengthCompare(currentTableSize) > 0) {
      addNewSongs(songs, currentTableSize)
    }
  }

  /**
    * Consumer function for new meta data for songs in the playlist.
    *
    * @param playlistMetaData the new meta data
    */
  private def handlePlaylistMetaData(playlistMetaData: PlaylistMetaData): Unit = {
    metaData = playlistMetaData.data
    if (unresolvedSongCount > 0) {
      val (min, max, unres) = applyNewMetaDataToTableModel(metaData, tableHandler.getModel)
      if (min >= 0) {
        tableHandler.rowsUpdated(min, max)
        updateStatusLine()
      }
      unresolvedSongCount = unres
    }
  }

  /**
    * Updates the table model with new meta data. Iterates over the table of
    * songs and applies meta data to items that have been resolved. Result is a
    * triple with indices determining the minimum and maximum indices that have
    * been updated. This is required to update the table model. A value of -1
    * for the minimum index means that no change was made. The third element in
    * the triple is the new number of unresolved songs.
    *
    * @param data   the current map with meta data
    * @param model  the table model collection
    * @return data about the outcome of this operation
    */
  private def applyNewMetaDataToTableModel(data: Map[MediaFileID, MediaMetaData],
                                                    model: java.util.List[AnyRef]):
  (Int, Int, Int) = {
    @tailrec def doApply(idx: Int, minIdx: Int, maxIdx: Int, unresolvedCount: Int):
    (Int, Int, Int) =
    if (idx >= model.size()) (minIdx, maxIdx, unresolvedCount)
    else {
      val song = model.get(idx).asInstanceOf[SongData]
      val unresolved = isSongUnresolved(song)
      if (unresolved && data.contains(song.id)) {
        model.set(idx, songDataFactory.createSongData(song.id, data(song.id)))
        doApply(idx + 1, if (minIdx < 0) idx else minIdx, idx, unresolvedCount)
      }
      else doApply(idx + 1, minIdx, maxIdx,
        if(unresolved) unresolvedCount + 1 else  unresolvedCount)
    }

    doApply(0, -1, -1, 0)
  }

  /**
    * Adds new songs to the managed table model.
    *
    * @param songs            the full playlist of the audio player
    * @param currentTableSize the current size of the managed table
    */
  private def addNewSongs(songs: List[MediaFileID], currentTableSize: Int): Unit = {
    val newSongs = songs.drop(currentTableSize)
      .map(id => songDataFactory.createSongData(id, metaData.getOrElse(id, UndefinedMetaData)))
    unresolvedSongCount += newSongs.count(isSongUnresolved)
    tableHandler.getModel addAll newSongs.asJava
    tableHandler.rowsInserted(currentTableSize, currentTableSize + newSongs.size - 1)
    updateStatusLine()
    updateActions()
    val newSelection = currentTableSize.until(currentTableSize + newSongs.size).toArray
    tableHandler setSelectedIndices newSelection
  }

  /**
    * Updates the status line with information about the current playlist. This
    * method is called after the playlist has been manipulated.
    */
  private def updateStatusLine(): Unit = {
    statusLine setText generateStatusLineText(tableHandler.getModel.asScala.toSeq, statusLineTemplate)
  }

  /**
    * Adapts the enabled state of managed actions after changes on the
    * playlist. This affects actions that have to be enabled if and only if the
    * current playlist contains elements.
    */
  private def updateActions(): Unit = {
    val enabled = !tableHandler.getModel.isEmpty
    ManagedActions foreach { act =>
      actionStore.getAction(act) setEnabled enabled
    }
  }
}
