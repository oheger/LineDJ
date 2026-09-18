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
import de.oliver_heger.linedj.platform.audio2.AudioPlayerState
import de.oliver_heger.linedj.platform.audio2.playlist.MediaFileInfoResolverService
import de.oliver_heger.linedj.platform.comm.MessageBusListener
import de.oliver_heger.linedj.platform.ui.DurationTransformer
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.model.{StaticTextHandler, TableHandler}
import org.apache.pekko.actor.Actor.Receive

import scala.collection.immutable.Seq
import scala.jdk.CollectionConverters.*
import scala.util.{Success, Try}

object PlaylistController:
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

  /**
    * Generates the text to be displayed in the status line based on the
    * specified template.
    *
    * @param songs    the current list with songs in the playlist
    * @param template the template for generating the status line
    * @return the text for the status line
    */
  private def generateStatusLineText(songs: Seq[Any], template: String): String =
    val songData = songs map (_.asInstanceOf[SongData])
    val (size, duration, durationDefined) = songs.foldLeft((0L, 0, true)):
      case ((size, duration, defined), s: SongData) =>
        val (nextDuration, nextDefined) = s.optFileInfo.flatMap(_.metadata.duration) match
          case None => (duration, false)
          case Some(d) => (duration + d, defined)
        (size + s.optFileInfo.map(_.metadata.size).getOrElse(0L), nextDuration, nextDefined)
      case (t, _) => t
    val totalDuration = DurationTransformer.formatDuration(duration)
    val actDuration = if durationDefined then totalDuration
    else IndicationUnknownDuration + totalDuration
    template.format(songs.size, actDuration, size / Megabyte)
end PlaylistController

/**
  * The controller class for the Playlist Editor application.
  *
  * This application allows defining a playlist. It displays all songs that have
  * been added to the current playlist (which is done from other applications). 
  * The list can then be manipulated, e.g. reordered.
  *
  * This controller class mainly manages a table control that displays the
  * songs in the current playlist. It reacts on updates of the audio player
  * state (which contains the current playlist) that are published on the 
  * system message bus. Since items in the playlist only contain a media file
  * ID, it has to fetch the associated metadata, so that meaningful information
  * can be displayed.
  *
  * The status line shows aggregated data over the songs in the playlist. The
  * concrete content is defined by a template passed to the constructor. This
  * string has to contain 3 "%" placeholders for the number of songs, the
  * duration, and the size in MB.
  *
  * @param resolverService    the service to resolve metadata for files
  * @param tableHandler       the handler for the playlist table widget
  * @param statusLine         the widget representing the status line
  * @param actionStore        the current ''ActionStore''
  * @param statusLineTemplate a localized template the status line
  * @param statusLineError    the text to show in the status line for an error
  */
class PlaylistController(resolverService: MediaFileInfoResolverService,
                         tableHandler: TableHandler,
                         statusLine: StaticTextHandler,
                         actionStore: ActionStore,
                         statusLineTemplate: String,
                         statusLineError: String) extends MessageBusListener:

  import PlaylistController.*

  /**
    * Updates the current playlist by invoking the specified
    * [[PlaylistManipulator]].
    *
    * @param manipulator the [[PlaylistManipulator]]
    */
  def updatePlaylist(manipulator: PlaylistManipulator): Unit =
    manipulator updatePlaylist PlaylistSelectionContext(tableHandler)
    updateStatusLine()
    updateActions()

  override def receive: Receive =
    case state: AudioPlayerState =>
      handleAudioPlayerStateChangeEvent(state)

  /**
    * Consumer function for state change events of the audio player. If new
    * songs have been added to the playlist, they are also added to managed
    * table.
    *
    * @param state the updated state of the audio player
    */
  private def handleAudioPlayerStateChangeEvent(state: AudioPlayerState): Unit =
    val songs = state.playlist.pendingSongs
    val currentTableSize = tableHandler.getModel.size()
    if songs.lengthCompare(currentTableSize) > 0 then
      addNewSongs(songs, currentTableSize)
      resolverService.resolveFileIDs(songs)(handleResolvedMediaFiles)

  /**
    * The callback function invoked by the media file resolver service when
    * metadata is available. If the operation was successful, the table model
    * is updated with the new data.
    *
    * @param triedResults the results from the resolve operation
    */
  private def handleResolvedMediaFiles(triedResults: Try[Map[String, ArchiveModel.MediaFileInfo]]): Unit =
    triedResults match
      case Success(results) =>
        val (minIndex, maxIndex) = tableHandler.getModel.asScala.zipWithIndex.foldLeft((-1, -1)):
          case ((minIndex, maxIndex), (s: SongData, index)) =>
            val songResolved = results.contains(s.fileID)
            if songResolved then
              val nextMinIndex = if minIndex < 0 then index else minIndex
              val nextMaxIndex = index
              tableHandler.getModel.set(index, s.copy(optFileInfo = results.get(s.fileID)))
              (nextMinIndex, nextMaxIndex)
            else
              (minIndex, maxIndex)
          case ((minIndex, maxIndex), (s: AnyRef, _)) => // Should not happen.
            (minIndex, maxIndex)
        if minIndex >= 0 then
          tableHandler.rowsUpdated(minIndex, maxIndex)
          updateStatusLine()
      case _ =>
        statusLine.setText(statusLineError)

  /**
    * Adds new songs to the managed table model.
    *
    * @param songs            the full playlist of the audio player
    * @param currentTableSize the current size of the managed table
    */
  private def addNewSongs(songs: List[String], currentTableSize: Int): Unit =
    val newSongs = songs.drop(currentTableSize)
      .map(id => SongData(id, None))
    tableHandler.getModel.addAll(newSongs.asJava)
    tableHandler.rowsInserted(currentTableSize, currentTableSize + newSongs.size - 1)
    updateStatusLine()
    updateActions()
    val newSelection = currentTableSize.until(currentTableSize + newSongs.size).toArray
    tableHandler.setSelectedIndices(newSelection)

  /**
    * Updates the status line with information about the current playlist. This
    * method is called after the playlist has been manipulated.
    */
  private def updateStatusLine(): Unit =
    val statusText = generateStatusLineText(tableHandler.getModel.asScala.toSeq, statusLineTemplate)
    statusLine.setText(statusText)

  /**
    * Adapts the enabled state of managed actions after changes on the
    * playlist. This affects actions that have to be enabled if and only if the
    * current playlist contains elements.
    */
  private def updateActions(): Unit =
    val enabled = !tableHandler.getModel.isEmpty
    ManagedActions foreach : act =>
      actionStore.getAction(act) setEnabled enabled
