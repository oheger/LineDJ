/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.browser.media

import java.util.Locale

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.browser.cache.{MetaDataRegistration, RemoveMetaDataRegistration}
import de.oliver_heger.linedj.browser.model.{SongData, SongDataFactory}
import de.oliver_heger.linedj.bus.MessageBusListener
import de.oliver_heger.linedj.media.{AvailableMedia, MediumInfo}
import de.oliver_heger.linedj.metadata.MetaDataChunk
import de.oliver_heger.linedj.remoting.MessageBus
import de.oliver_heger.linedj.remoting.RemoteRelayActor.ServerUnavailable
import net.sf.jguiraffe.gui.builder.components.WidgetHandler
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, TableHandler,
TreeHandler, TreeNodePath}

import scala.annotation.tailrec

object MediaController {
  /**
   * Creates an ''AlbumKey'' object for the specified song.
   * @param song the ''SongData''
   * @return the ''AlbumKey'' for this song
   */
  private def createAlbumKey(song: SongData): AlbumKey = {
    AlbumKey(artist = upper(song.getArtist), album = upper(song.getAlbum))
  }

  /**
   * Transforms a string to upper case.
   * @param s the string
   * @return the string in upper case
   */
  private def upper(s: String): String = s toUpperCase Locale.ENGLISH

  /**
   * Processes the specified selection of tree nodes and obtains the keys for
   * the albums to be added to the table view.
   * @param paths the selected paths in the tree view
   * @return a sequence of album keys to be displayed
   */
  private def fetchSelectedAlbumKeys(paths: Array[TreeNodePath]): Iterable[AlbumKey] = {
    paths map (_.getTargetNode.getValue) filter (_.isInstanceOf[AlbumKey]) map (_
      .asInstanceOf[AlbumKey])
  }

  /**
   * An internally used data class which collects all information required to
   * update internal model classes with newly arrived chunks of meta data.
   * @param treeModel the model for the tree view
   * @param tableModel the model for the table view
   * @param updaterMap a map with the updaters for the different artists
   */
  private case class ModelUpdateData(treeModel: MediumTreeModel, tableModel: AlbumTableModel,
                                     updaterMap: Map[String, ConfigurationUpdater])

  /**
   * An internally used data class which stores the different models containing
   * the data for view components.
   * @param treeModel the model for the tree view
   * @param tableModel the model for the table view
   */
  private case class Models(treeModel: MediumTreeModel, tableModel: AlbumTableModel)

}

/**
 * The controller class for the media view.
 *
 * This controller manages a view consisting of a combo box for selecting a
 * medium, a tree view displaying the artists and albums found in the medium,
 * and a table showing the songs of the albums selected in the tree view.
 *
 * Selecting a medium in the combo box causes a request for the meta data of
 * this medium. Incoming chunks of meta data are then added to the data models
 * of the controls. When the server becomes unavailable the combo box is
 * disabled, so that the user cannot select a new medium for which no meta data
 * is available.
 *
 * @param messageBus the message bus
 * @param songFactory the factory for ''SongData'' objects
 * @param comboMedia the handler for the combo with the media
 * @param treeHandler the handler for the tree view
 * @param tableHandler the handler for the table
 * @param inProgressWidget the widget handler for the in-progress indicator
 */
class MediaController(messageBus: MessageBus, songFactory: SongDataFactory, comboMedia:
ListComponentHandler, treeHandler: TreeHandler, tableHandler: TableHandler, inProgressWidget:
                      WidgetHandler) extends
MessageBusListener {

  import MediaController._

  /** The model of the tree view. */
  private val treeModel = treeHandler.getModel

  /** The model of the table. */
  private val tableModel = tableHandler.getModel

  /**
   * An option with the currently selected medium URI. This is changed via the
   * combo box with the available media.
   */
  private var selectedMediumURI: Option[String] = None

  /** The underlying model for the view controls. */
  private var models: Option[Models] = None

  /** A set with the keys of the currently selected albums. */
  private var selectedAlbumKeys = Set.empty[AlbumKey]

  /**
   * Returns the function for handling messages published on the message bus.
   * @return the message handling function
   */
  override def receive: Receive = {
    case ServerUnavailable =>
      comboMedia setEnabled false

    case AvailableMedia(media) =>
      selectedMediumURI = None
      removeExistingMediaFromComboBox()
      addMediaToComboBox(media)
      comboMedia setEnabled true
  }

  /**
   * Selects the specified medium. This method is called when the combo box
   * selection is changed. This causes the data of the new medium to be
   * requested and displayed.
   * @param mediumURI the URI of the newly selected medium
   */
  def selectMedium(mediumURI: String): Unit = {
    selectedMediumURI foreach clearOldMediumSelection
    messageBus publish MetaDataRegistration(mediumURI, this)(processMetaDataChunk)
    selectedMediumURI = Some(mediumURI)
    inProgressWidget setVisible true
  }

  /**
   * Notifies this controller that the selection of the tree view has changed.
   * The table has to be updated accordingly to display all selected albums.
   * @param paths the paths representing the selection of the tree view
   */
  def selectAlbums(paths: Array[TreeNodePath]): Unit = {
    models foreach { m =>
      val keys = fetchSelectedAlbumKeys(paths)
      selectedAlbumKeys = keys.toSet
      fillTableModelForSelection(m.tableModel, keys)
    }
  }

  /**
   * Processes a chunk of meta data when it arrives. The existing data models
   * for the views are updated accordingly.
   * @param chunk the chunks that was received
   */
  private def processMetaDataChunk(chunk: MetaDataChunk): Unit = {
    selectedMediumURI foreach { uri =>
      if (chunk.mediumID == uri) {
        addMetaDataChunk(chunk)
      }
    }
  }

  /**
   * Actually adds the specified chunk of meta data to the models maintained by
   * this controller class.
   * @param chunk the chunk of data to be added
   */
  private def addMetaDataChunk(chunk: MetaDataChunk): Unit = {
    val songData = chunk.data.toList map { e =>
      val song = songFactory.createSongData(e._1, e._2)
      (createAlbumKey(song), song)
    }

    val nextModels = models match {
      case None =>
        initializeModels(songData)

      case Some(m) =>
        updateModels(songData, m)
    }
    models = Some(nextModels)

    if (albumSelectionAffectedByChunk(songData)) {
      fillTableModelForSelection(nextModels.tableModel, selectedAlbumKeys)
    }
    if (chunk.complete) {
      inProgressWidget setVisible false
    }
  }

  /**
   * Checks whether a new chunk of meta data has an impact on the data
   * currently displayed in the table view.
   * @param songData the songs contained in the chunk
   * @return a flag whether the table view has to be updated
   */
  private def albumSelectionAffectedByChunk(songData: List[(AlbumKey, SongData)]): Boolean = {
    selectedAlbumKeys.nonEmpty && selectedAlbumKeys.intersect(songData.map(_._1).toSet).nonEmpty
  }

  /**
   * Initializes new model objects from the data of the specified chunk. This
   * method is called when the first chunk for a medium is received.
   * @param songData the list with data to be added to the models
   * @return the initialized models
   */
  private def initializeModels(songData: List[(AlbumKey, SongData)]): Models = {
    val mediumTreeModel = MediumTreeModel(songData)
    mediumTreeModel.fullUpdater().update(treeModel, mediumTreeModel)
    Models(treeModel = mediumTreeModel, tableModel = AlbumTableModel(songData))
  }

  /**
   * Updates the current model objects with the data from the specified chunk.
   * This method is called for further chunks received for a medium.
   * @param songData the list with data to be added to the models
   * @param models the current models
   * @return the updated models
   */
  private def updateModels(songData: List[(AlbumKey, SongData)], models: Models):
  Models = {
    val updateData = songData.foldLeft(ModelUpdateData(models.treeModel, models.tableModel, Map
      .empty)) {
      (data, item) =>
        val updater = data.updaterMap.getOrElse(item._1.artist, NoopUpdater)
        val (nextModel, nextUpdater) = data.treeModel.add(item._1, item._2, updater)
        val nextMap = data.updaterMap + (item._1.artist -> nextUpdater)
        val nextTableModel = data.tableModel.add(item._1, item._2)
        ModelUpdateData(nextModel, nextTableModel, nextMap)
    }
    updateData.updaterMap.values foreach (_.update(treeModel, updateData.treeModel))
    Models(updateData.treeModel, updateData.tableModel)
  }

  /**
   * Populates the collection serving as table model after a change in the
   * album selection.
   * @param model the underlying table model object
   * @param selectedKeys the keys of the selected albums
   */
  private def fillTableModelForSelection(model: AlbumTableModel, selectedKeys:
  Iterable[AlbumKey]): Unit = {
    tableModel.clear()
    selectedKeys foreach { key =>
      val songs = model songsFor key
      songs foreach tableModel.add
    }
    tableHandler.tableDataChanged()
  }

  /**
   * Clears the current list model for the combo box.
   */
  private def removeExistingMediaFromComboBox(): Unit = {
    @tailrec def clearListModel(index: Int): Unit = {
      if (index >= 0) {
        comboMedia removeItem index
        clearListModel(index - 1)
      }
    }

    clearListModel(comboMedia.getListModel.size() - 1)
  }

  /**
   * Performs cleanup before a new medium is selected.
   * @param mediumURI the URI of the last selected medium
   */
  private def clearOldMediumSelection(mediumURI: String): Unit = {
    messageBus publish RemoveMetaDataRegistration(mediumURI, this)
    models = None
    treeModel.clear()
    tableModel.clear()
    selectedAlbumKeys = Set.empty
  }

  /**
   * Adds all available media to the combo box. The entries are ordered by the
   * names of the media.
   * @param media the map with available media
   */
  private def addMediaToComboBox(media: Map[String, MediumInfo]): Unit = {
    val orderedMedia = media.toList sortWith (_._2.name < _._2.name)
    orderedMedia.zipWithIndex.foreach(e => comboMedia.addItem(e._2, e._1._2.name, e._1._1))
  }
}
