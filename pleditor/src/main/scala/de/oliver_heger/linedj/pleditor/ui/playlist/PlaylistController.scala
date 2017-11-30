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

package de.oliver_heger.linedj.pleditor.ui.playlist

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.audio.model.{AppendSongs, DurationTransformer, SongData}
import de.oliver_heger.linedj.platform.comm.MessageBusListener
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.model.{StaticTextHandler, TableHandler}

import scala.collection.JavaConversions._

object PlaylistController {
  /** Factor for the size of a Megabyte. */
  private val Megabyte = 1024 * 1024.0

  /** A string serving as indication that there are songs with unknown duration. */
  private val IndicationUnknownDuration = "> "

  /** The name of the export action. */
  private val ActionExport = "plExportAction"

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
 */
class PlaylistController(tableHandler: TableHandler, statusLine: StaticTextHandler,
                         actionStore: ActionStore, statusLineTemplate: String) extends
  MessageBusListener {
  import PlaylistController._

  /**
   * @inheritdoc This implementation mainly reacts on messages indicating that
   *             new songs have to be added to the playlist.
   */
  override def receive: Receive = {
    case AppendSongs(songs) =>
      val currentSize = tableHandler.getModel.size()
      tableHandler.getModel addAll songs
      tableHandler.rowsInserted(currentSize, currentSize + songs.size - 1)
      updateStatusLine()
      updateActions()
      val newSelection = currentSize.until(currentSize + songs.size).toArray
      tableHandler setSelectedIndices newSelection
  }

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
  }

  /**
    * Updates the status line with information about the current playlist. This
    * method is called after the playlist has been manipulated.
    */
  private def updateStatusLine(): Unit = {
    statusLine setText generateStatusLineText(tableHandler.getModel, statusLineTemplate)
  }

  /**
    * Adapts the enabled state of managed actions after changes on the
    * playlist. This affects actions that have to be enabled if and only if the
    * current playlist contains elements.
    */
  private def updateActions(): Unit = {
    actionStore.getAction(ActionExport) setEnabled !tableHandler.getModel.isEmpty
  }
}
