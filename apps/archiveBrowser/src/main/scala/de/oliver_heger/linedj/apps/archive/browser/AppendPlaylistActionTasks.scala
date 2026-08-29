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

package de.oliver_heger.linedj.apps.archive.browser

import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.platform.archiveclient.ArchiveService
import de.oliver_heger.linedj.platform.audio2.AudioPlayerCommands
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, TableHandler, TreeHandler}
import org.apache.logging.log4j.LogManager

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
  * An action task implementation to publish a command on the message bus that
  * adds all songs contained in a table to the current playlist. Optionally, it
  * can be specified that only selected songs are appended.
  *
  * @param messageBus   the message bus
  * @param table        the table with the songs
  * @param selectedOnly flag that only selected songs should be appended
  */
class AppendTableSongsTask(messageBus: MessageBus,
                           table: TableHandler,
                           selectedOnly: Boolean) extends Runnable:
  override def run(): Unit =
    import scala.jdk.CollectionConverters.*
    val filteredSongs = if selectedOnly then
      table.getSelectedIndices.map(table.getModel.get).toList
    else table.getModel.asScala

    val songIDs = filteredSongs.map: data =>
      data.asInstanceOf[SongData].metadata.checksum
    val command = AudioPlayerCommands.AppendPlaylist(songIDs.toList)
    messageBus.publish(command)

/**
  * An action task implementation to publish a command on the message bus that
  * adds all songs of a specific artist to the current playlist. The command
  * determines the selected medium and the selected artist. It then fetches the
  * songs of this artist from the [[ArchiveService]] (since these songs have 
  * not been loaded to any UI control).
  *
  * @param archiveService   the archive service
  * @param executionContext the execution context
  * @param messageBus       the message bus
  * @param artistTree       the handler for the artists tree
  * @param mediumList       the handler for the list of media
  */
class AppendArtistSongsTask(archiveService: ArchiveService,
                            executionContext: ExecutionContext,
                            messageBus: MessageBus,
                            artistTree: TreeHandler,
                            mediumList: ListComponentHandler) extends Runnable, ArchiveModel.ArchiveJsonSupport:
  private given ExecutionContext = executionContext

  /** The logger. */
  private val log = LogManager.getLogger(getClass)

  override def run(): Unit =
    val mediumID = mediumList.getData.asInstanceOf[Checksums.MediumChecksum]
    val selectedPath = artistTree.getSelectedPath
    val artistPath = selectedPath.getTargetNode.getValue match
      case _: Controller.AlbumID => selectedPath.parentPath()
      case _ => selectedPath
    val artistID = artistPath.getTargetNode.getValue.asInstanceOf[Controller.SongOwnerID]
    val artistUrl = s"/api/archive/media/${mediumID.checksum}/artists/${artistID.id}/songs"
    archiveService.queryData[ArchiveModel.ItemsResult[MediaMetadata]](artistUrl) onComplete :
      case Success(value) =>
        val command = AudioPlayerCommands.AppendPlaylist(value.items.map(_.checksum))
        messageBus.publish(command)
      case Failure(exception) =>
        log.error("Failed to load songs for artist at '{}'.", artistUrl, exception)
