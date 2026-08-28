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

import de.oliver_heger.linedj.apps.archive.browser.Controller.{AlbumID, ArtistID, MediaChanged, MediumData, SongOwnerID}
import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.platform.archiveclient.ArchiveStateMonitor
import de.oliver_heger.linedj.platform.comm.MessageBusListener
import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, TableHandler, TreeHandler, TreeNodePath}
import net.sf.jguiraffe.resources.Message
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.Actor.Receive

import java.util.Locale
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object Controller:
  /** The resource ID of the medium loading message. */
  private[browser] val ResMediumLoading = "stat_medium_loading"

  /** The resource ID to display an error while loading data. */
  private[browser] val ResErrorLoading = "stat_loading_error"

  /**
    * An internally used message class the controller sends to itself when it
    * is notified about a change in the media data of the connected archive
    * server.
    *
    * @param media the updated media data
    */
  private[browser] case class MediaChanged(media: ArchiveModel.MediaOverview)

  /**
    * A trait defining the ID of an entity that is associated with songs. Such
    * entities can be selected in the UI. Based on the concrete ID type,
    * different queries need to be sent to the archive to load the correct
    * songs.
    */
  private[browser] sealed trait SongOwnerID:
    /**
      * Returns the string value of the represented ID.
      *
      * @return the ID as string
      */
    def id: String

  /**
    * An internally used data class to represent the ID of an artist.
    *
    * @param id the alphanumeric artist ID
    */
  private[browser] case class ArtistID(override val id: String) extends SongOwnerID

  /**
    * An internally used data class to represent the ID of an artist who does
    * not have any albums. If such an artist is selected, their songs are shown
    * in the artist songs table.
    *
    * @param id the alphanumeric artist ID
    */
  private[browser] case class ArtistWithoutAlbumsID(override val id: String) extends SongOwnerID

  /**
    * An internally used data class to represent the ID of an album.
    *
    * @param id the alphanumeric album ID
    */
  private[browser] case class AlbumID(override val id: String) extends SongOwnerID

  /** The logger. */
  private val log = LogManager.getLogger(classOf[Controller])

  /**
    * An internally used data class that stores the relevant data of a medium.
    * When the user selects another medium, the controller loads data from the
    * archive and creates an instance of this class. This is then used to
    * populate the UI elements.
    *
    * @param mediumDetails  detail information about the medium
    * @param artistData     a list with data about the artists of the medium
    * @param albumData      a list with data about the albums of the medium
    * @param albumsByArtist a map with the albums keyed by artist ID
    */
  private case class MediumData(mediumDetails: ArchiveModel.MediumDetails,
                                artistData: List[ArchiveModel.ArtistInfo],
                                albumData: List[ArchiveModel.AlbumInfo],
                                albumsByArtist: Map[ArtistID, List[ArchiveModel.AlbumInfo]])

  /**
    * An internally used data class that reports an error when obtaining the
    * data for a medium. The controller sends an instance to itself over the
    * message bus, so that it can handle the error in the UI thread.
    *
    * @param mediumID  the ID of the affected medium
    * @param exception the exception that occurred
    */
  private case class MediumError(mediumID: Checksums.MediumChecksum,
                                 exception: Throwable)

  /** An ordering for sorting media in the combobox. */
  private given mediumOverviewOrdering: Ordering[ArchiveModel.MediumOverview] =
    Ordering.by(_.title.toLowerCase(Locale.ROOT))

  /**
    * Generates the base URL for a request to the archive server for a specific
    * medium.
    *
    * @param mediumID the ID of the medium
    * @return the URL to access this medium
    */
  private def archiveMediumUrl(mediumID: Checksums.MediumChecksum): String =
    s"/api/archive/media/${mediumID.checksum}"
end Controller

/**
  * Controller class for the archive browser application.
  *
  * This class manages various controls to browse the content of a media
  * archive hosted by a connected archive server. Interaction with this server
  * takes place via the [[ArchiveService]] passed to the [[SongsLoader]]s. The
  * UI contains a combobox to select a specific medium. It then shows different
  * views of the songs stored on the selected medium.
  *
  * @param songsLoader       the loader and cache for the songs of albums
  * @param artistSongsLoader the loader and cache for the songs of artists
  * @param comboMedia        the combobox to select a medium
  * @param treeArtists       the handler for the tree with artist info
  * @param tabArtistSongs    the table for the songs in the artist view
  * @param tabAlbums         the table for the albums on a medium
  * @param tabAlbumSongs     the table for the songs of the selected album
  */
class Controller(songsLoader: SongsLoader,
                 artistSongsLoader: SongsLoader,
                 comboMedia: ListComponentHandler,
                 treeArtists: TreeHandler,
                 tabArtistSongs: TableHandler,
                 tabAlbums: TableHandler,
                 tabAlbumSongs: TableHandler)
  extends ArchiveStateMonitor.ArchiveChangeListener[ArchiveModel.MediaOverview], MessageBusListener,
    ArchiveModel.ArchiveJsonSupport:

  import songsLoader.*

  /** Provides an execution context in implicit scope. */
  private given ExecutionContext = executionContext

  /** Holds the currently selected medium if any. */
  private var optSelectedMedium: Option[Checksums.MediumChecksum] = None

  import Controller.*

  /**
    * Initializes this controller. This function is called by the DI framework
    * when the bean is created.
    */
  def initialize(): Unit =
    archiveService.addChangeListener(this)

  /**
    * Destroys this controller. Performs cleanup. This function is called by
    * the DI framework when the application shuts down.
    */
  def destroy(): Unit =
    archiveService.removeChangeListener(this)

  override def archiveStateChanged(state: ArchiveModel.MediaOverview): Unit =
    messageBus.publish(MediaChanged(state))

  override def receive: Receive =
    case MediaChanged(media) =>
      updateMedia(media)

    case data: MediumData =>
      statusController.loadOperationEnds()
      updateUI(data)

    case error: MediumError =>
      statusController.loadOperationEnds()
      handleMediumError(error)

  /**
    * Notifies this controller about a change in the selection of the media 
    * combobox. If a medium is selected, the controller loads its data and 
    * displays it in the managed controls. An empty option means that nothing
    * is selected.
    *
    * @param optMediumID the optional ID and title of the selected medium
    */
  private[browser] def mediumSelected(optMediumID: Option[Checksums.MediumChecksum]): Unit =
    treeArtists.getModel.clear()
    tabArtistSongs.getModel.clear()
    tabAlbums.getModel.clear()
    tabAlbumSongs.getModel.clear()
    songsLoader.mediumSelectionChanged()
    artistSongsLoader.mediumSelectionChanged()
    optSelectedMedium = optMediumID

    optMediumID match
      case Some(mediumID) =>
        statusController.loadOperationStarts()
        statusController.setStatusMessage(new Message(null, ResMediumLoading, mediumID.checksum))
        val mediumUrl = archiveMediumUrl(mediumID)
        val futDetails = archiveService.queryData[ArchiveModel.MediumDetails](mediumUrl)
        val artistsUrl = s"$mediumUrl/artists"
        val futArtists = archiveService.queryData[ArchiveModel.ItemsResult[ArchiveModel.ArtistInfo]](artistsUrl)
        val albumsUrl = s"$mediumUrl/albums"
        val futAlbums = archiveService.queryData[ArchiveModel.ItemsResult[ArchiveModel.AlbumInfo]](albumsUrl)

        (for
          details <- futDetails
          artists <- futArtists
          albums <- futAlbums
        yield
          createMediumData(details, artists, albums)
          ).onComplete:
          case Success(mediumData) =>
            messageBus.publish(mediumData)
          case Failure(exception) =>
            log.error("Failed to load data for medium '{}'.", mediumID, exception)
            messageBus.publish(MediumError(mediumID, exception))
      case None =>
        statusController.setMediumTitle(None)

  /**
    * Notifies this controller about a change in the selection of the albums of
    * an artist. This means that the songs of the corresponding album or
    * artist need to be shown in the table view. Based on the concrete type
    * of the ID, the appropriate songs loader is used.
    *
    * @param optOwnerID the optional selected song owner ID
    */
  private[browser] def artistAlbumSelected(optOwnerID: Option[SongOwnerID]): Unit =
    optOwnerID match
      case Some(_: ArtistID) =>
        clearArtistSongsTable()
      case Some(ArtistWithoutAlbumsID(id)) =>
        artistSongsLoader.fetchSongs(optSelectedMedium.map(_.checksum), id, tabArtistSongs)
      case Some(AlbumID(id)) =>
        songsLoader.fetchSongs(optSelectedMedium.map(_.checksum), id, tabArtistSongs)
      case None =>
        clearArtistSongsTable()

  /**
    * Notifies this controller about a change in the selection of the table
    * with the albums of the current medium. This means that the songs of the
    * newly selected album need to be displayed in the associated songs
    * table. The songs loader shared with the artist view is used, so that
    * cached data can be reused.
    *
    * @param optAlbumID the optional ID of the selected album
    */
  private[browser] def albumSelected(optAlbumID: Option[AlbumID]): Unit =
    optAlbumID match
      case Some(albumID) =>
        songsLoader.fetchSongs(optSelectedMedium.map(_.checksum), albumID.id, tabAlbumSongs)
      case None =>
        tabAlbumSongs.getModel.clear()
        tabAlbumSongs.tableDataChanged()

  /**
    * Clears the table with the songs of the current artist.
    */
  private def clearArtistSongsTable(): Unit =
    tabArtistSongs.getModel.clear()
    tabArtistSongs.tableDataChanged()

  /**
    * Updates the combobox with media to contain exactly the given data. This
    * function is called when an update notification for media data is 
    * received.
    *
    * @param media the updated media data
    */
  private def updateMedia(media: ArchiveModel.MediaOverview): Unit =
    val currentSelection = comboMedia.getData

    removeExistingMediaFromComboBox()
    import Controller.mediumOverviewOrdering
    media.media.sorted.zipWithIndex.foreach:
      case (medium, index) =>
        comboMedia.addItem(index, medium.title, medium.id)

    val nextSelection = if currentSelection != null && media.media.exists(_.id == currentSelection) then
      currentSelection
    else
      null
    comboMedia.setData(nextSelection)

  /**
    * Clears the current list model for the combo box.
    */
  private def removeExistingMediaFromComboBox(): Unit =
    @tailrec def clearListModel(index: Int): Unit =
      if index >= 0 then
        comboMedia removeItem index
        clearListModel(index - 1)

    clearListModel(comboMedia.getListModel.size() - 1)

  /**
    * Creates a [[MediumData]] object from the given input that was requested
    * from the archive server.
    *
    * @param details      the details of the selected medium
    * @param artistResult the artists of the current medium
    * @param albumResult  the albums of the current medium
    * @return the [[MediumData]] for this medium
    */
  private def createMediumData(details: ArchiveModel.MediumDetails,
                               artistResult: ArchiveModel.ItemsResult[ArchiveModel.ArtistInfo],
                               albumResult: ArchiveModel.ItemsResult[ArchiveModel.AlbumInfo]): MediumData =
    val albumsByArtist = albumResult.items.groupBy(album => ArtistID(album.artistId))
    MediumData(details, artistResult.items, albumResult.items, albumsByArtist)

  /**
    * Populates the UI elements with the data for a newly selected medium.
    *
    * The tree view for artists and their albums is defined by a hierarchical
    * configuration structure. Here, the node values are IDs of entities 
    * represented by specific classes. That way, the code that reacts on 
    * selection changes can determine, what is currently selected and fetch the
    * correct data. The following cases need to be distinguished:
    *  - The ID of an album; then the songs of this album are displayed in the
    *    artist songs table.
    *  - The ID of an artist with no associated albums; then all the songs of
    *    this artist are displayed in the artist songs table.
    *  - The ID of an artist; then the artist songs table remains empty.
    *
    * Note that the artist node has to be added before its albums. Otherwise,
    * JGUIraffe's change handler reports the (new) artist node as the node
    * affected by an album update; since this node does not yet have a tree
    * item, the update is lost, and the tree remains empty on subsequent
    * medium selections.
    *
    * @param mediumData the data for the medium
    */
  private def updateUI(mediumData: MediumData): Unit =
    if isCurrentMedium(mediumData.mediumDetails.id) then
      statusController.setMediumTitle(Some(mediumData.mediumDetails.title))
      mediumData.artistData.foreach: artistInfo =>
        val artistKey = artistInfo.artistName
        mediumData.albumsByArtist.get(ArtistID(artistInfo.id)) match
          case Some(albums) =>
            treeArtists.getModel.addProperty(artistKey, ArtistID(artistInfo.id))
            albums.foreach: album =>
              val configKey = s"$artistKey|${album.albumName}"
              treeArtists.getModel.addProperty(configKey, AlbumID(album.id))
          case None =>
            treeArtists.getModel.addProperty(artistKey, ArtistWithoutAlbumsID(artistInfo.id))
      treeArtists.clearSelection()
      val rootPath = new TreeNodePath(treeArtists.getModel.getRoot)
      treeArtists.collapse(rootPath)
      treeArtists.expand(rootPath)
      populateAlbumsTable(mediumData)

  /**
    * Populates the albums table with the data from the given medium.
    * For each album, the album title and the artist name are added. If the
    * artist name cannot be resolved, an empty string is used.
    *
    * @param mediumData the data for the medium
    */
  private def populateAlbumsTable(mediumData: MediumData): Unit =
    val artistNames = mediumData.artistData.map(a => a.id -> a.artistName).toMap
    mediumData.albumData.foreach: albumInfo =>
      val artistName = artistNames.getOrElse(albumInfo.artistId, "")
      tabAlbums.getModel.add(AlbumData(albumInfo, artistName))
    tabAlbums.tableDataChanged()

  /**
    * Updates the UI when data about a medium could not be loaded.
    *
    * @param error the object with information about the error
    */
  private def handleMediumError(error: MediumError): Unit =
    if isCurrentMedium(error.mediumID) then
      val statusMessage = new Message(null, ResErrorLoading, error.exception.getMessage)
      statusController.setStatusMessage(statusMessage)

  /**
    * Returns a flag whether the given medium ID refers to the currently
    * selected medium.
    *
    * @param id the ID in question
    * @return *true* if this is the current medium, *false* otherwise
    */
  private def isCurrentMedium(id: Checksums.MediumChecksum): Boolean = optSelectedMedium.contains(id)
