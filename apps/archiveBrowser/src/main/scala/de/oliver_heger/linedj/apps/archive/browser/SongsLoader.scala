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
import de.oliver_heger.linedj.platform.comm.{MessageBus, MessageBusListener}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import net.sf.jguiraffe.resources.Message
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.Actor.Receive

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object SongsLoader:
  /** The resource ID of the message when the songs of an album are loaded. */
  private[browser] val ResAlbumLoading = "stat_album_loading"

  /** The resource ID of the message when the songs of an artist are loaded. */
  private[browser] val ResArtistLoading = "stat_artist_loading"

  /** The placeholder for the medium ID to be replaced in the URL pattern. */
  private[browser] val MediumIDPlaceholder = "{mediumID}"

  /** A [[MediaMetadata]] object without any content. */
  private val DummyMetadata = MediaMetadata(size = 0, checksum = "")

  /**
    * A data object wrapping the dummy metadata. This is needed to work around
    * a bug of the JavaFX table view that does not update itself correctly if
    * only a single row is added.
    */
  private val DummySongData = SongData(DummyMetadata)

  /**
    * An internally used message class that reports the result of a request
    * for the songs of an entity. An instance is published on the message bus,
    * so that the result can be processed in the UI thread.
    *
    * @param id     the ID of the entity the songs belong to
    * @param songs  the songs that have been loaded from the archive server
    * @param sender a reference to the loader instance that sent this message
    */
  private[browser] case class SongsLoaded(id: String,
                                          songs: List[MediaMetadata],
                                          sender: AnyRef)

  /**
    * An internally used message class that reports an error when requesting
    * the songs of an entity. The loader sends an instance over the message
    * bus, so that the error can be handled in the UI thread.
    *
    * @param id        the ID of the affected entity
    * @param exception the exception that occurred
    * @param sender    a reference to the loader instance that sent this message
    */
  private[browser] case class SongsLoadError(id: String,
                                             exception: Throwable,
                                             sender: AnyRef)

  /**
    * An internally used data class to manage a request for the songs of an
    * entity. The songs are to be displayed in the referenced table. There is
    * a single instance per managed table; when a new selection is made for
    * this table, its instance is replaced.
    *
    * @param table    the table in which the songs are to be shown
    * @param entityID the ID of the entity the songs belong to
    */
  private case class SongRequest(table: TableHandler, entityID: String)
end SongsLoader

/**
  * An internally used helper class to load specific songs from an archive
  * server that are then added to table models. The songs are cached by the ID
  * of the owning entity as long as there is no change in the selected medium.
  * Multiple tables can be served concurrently: for each table, the loader
  * keeps track of the entity whose songs are currently requested, so that
  * results of requests that became outdated in the meantime are ignored.
  * All methods are expected to be called in the event dispatch thread. Songs
  * are requested asynchronously; by using the message bus, the synchronization
  * with the UI thread is done.
  *
  * This class is a message bus listener, so that it can react on the results
  * of asynchronous requests for songs.
  *
  * @param archiveService   the service to interact with the archive
  * @param executionContext the execution context
  * @param messageBus       the message bus
  * @param statusController the status line controller
  * @param urlPattern       a pattern to construct the URL to request songs;
  *                         the placeholder {mediumID} is replaced by the
  *                         medium's checksum
  * @param loadingResource  the resource ID for the status line message when
  *                         a loading operation starts
  */
private class SongsLoader(val archiveService: ArchiveService,
                          val executionContext: ExecutionContext,
                          val messageBus: MessageBus,
                          val statusController: StatusLineController,
                          urlPattern: String,
                          loadingResource: String)
  extends MessageBusListener, ArchiveModel.ArchiveJsonSupport:

  import SongsLoader.*

  /** The logger. */
  private val log = LogManager.getLogger(classOf[SongsLoader])

  /** Provides an execution context in implicit scope. */
  private given ExecutionContext = executionContext

  /** The cache with songs that have already been loaded for entities. */
  private var songCache = Map.empty[String, List[MediaMetadata]]

  /**
    * A list with data about the requests for songs that are currently in
    * progress. This list can contain an entry per served table, so that
    * multiple requests can be handled concurrently, e.g. when the songs of
    * different albums are displayed in different tables. Entries are removed
    * when their response has been processed or when a table is served from
    * the cache; therefore, this list contains exactly the loads that await
    * their response.
    */
  private var songRequests = List.empty[SongRequest]

  /**
    * @inheritdoc This implementation processes results of requests for songs.
    *             The loaded songs are cached and all tables that are waiting
    *             for this result are populated. The handled requests are then
    *             removed from the list of current requests. Errors are
    *             reported via the status line controller if the affected
    *             entity is still requested.
    */
  override def receive: Receive =
    case SongsLoaded(id, songs, loader) if loader eq this =>
      statusController.loadOperationEnds()
      songCache += id -> songs
      songRequests.filter(_.entityID == id).foreach: request =>
        populateTable(songs, request.table)
      songRequests = songRequests.filterNot(_.entityID == id)

    case error: SongsLoadError if error.sender eq this =>
      statusController.loadOperationEnds()
      if songRequests.exists(_.entityID == error.id) then
        val statusMessage = new Message(null, Controller.ResErrorLoading, error.exception.getMessage)
        statusController.setStatusMessage(statusMessage)
      songRequests = songRequests.filterNot(_.entityID == error.id)

  /**
    * Requests the songs for the entity with the given ID from this loader and
    * populates a table model with them. Depending on the configured
    * [[urlPattern]], the ID is interpreted either as an artist ID or album ID.
    * If no medium is currently selected, this method has no effect. If the
    * requested songs have already been loaded before, the provided table can
    * be populated directly; its registration for pending requests is removed
    * in this case, so that it cannot be updated by outdated responses. If a
    * request for the same entity is already in progress, the table is only
    * registered to receive the expected result. Otherwise, a new request is
    * sent now, and the table is filled when the response arrives.
    *
    * @param optMediumChecksum the optional checksum of the current medium
    * @param id                the ID of the owning entity
    * @param table             the handler of the table to be filled
    */
  def fetchSongs(optMediumChecksum: Option[String], id: String, table: TableHandler): Unit =
    optMediumChecksum match
      case Some(mediumChecksum) =>
        table.getModel.clear()
        songCache.get(id) match
          case Some(songs) =>
            removeSongRequest(table)
            populateTable(songs, table)
          case None =>
            val alreadyLoading = songRequests.exists(_.entityID == id)
            updateSongRequest(table, id)
            if !alreadyLoading then
              statusController.loadOperationStarts()
              statusController.setStatusMessage(new Message(null, loadingResource, id))
              val url = urlPattern.replace(MediumIDPlaceholder, mediumChecksum) + s"/$id/songs"
              val future = archiveService.queryData[ArchiveModel.ItemsResult[MediaMetadata]](url)
              future.onComplete:
                case Success(result) =>
                  messageBus.publish(SongsLoaded(id, result.items, sender = this))
                case Failure(exception) =>
                  log.error("Failed to load songs for entity '{}'.", id, exception)
                  messageBus.publish(SongsLoadError(id, exception, sender = this))
      case None =>

  /**
    * Notifies this object about a change in the medium selection. If this
    * happens, all internal data and caches are cleaned.
    */
  def mediumSelectionChanged(): Unit =
    songCache = Map.empty
    songRequests = List.empty

  /**
    * Replaces the request for songs that is managed for the given table by a
    * new one for the entity with the given ID. Results of outdated requests
    * for this table that arrive later are ignored automatically.
    *
    * @param table the table whose selection has changed
    * @param id    the ID of the newly selected entity
    */
  private def updateSongRequest(table: TableHandler, id: String): Unit =
    songRequests = songRequests.filterNot(_.table eq table) :+ SongRequest(table, id)

  /**
    * Removes the entry for the given table from the list of managed song
    * requests. This is used when a table is served from the cache. There is
    * no pending load for this table anymore; in particular, it must not be
    * updated by responses for entities it had selected before.
    *
    * @param table the table to be removed from the request list
    */
  private def removeSongRequest(table: TableHandler): Unit =
    songRequests = songRequests.filterNot(_.table eq table)

  /**
    * Populates the given table with the given songs.
    *
    * @param songs the songs to be shown in the table
    * @param table the table to be populated
    */
  private def populateTable(songs: List[MediaMetadata], table: TableHandler): Unit =
    table.getModel.clear()
    songs.foreach(song => table.getModel.add(SongData(song)))

    // This is a hacky workaround. If the new table content consists only of a
    // single row, for unknown reasons the table does not update itself.
    // Therefore, in this case a dummy row is inserted.
    if songs.size == 1 then
      table.getModel.add(DummySongData)

    table.tableDataChanged()
