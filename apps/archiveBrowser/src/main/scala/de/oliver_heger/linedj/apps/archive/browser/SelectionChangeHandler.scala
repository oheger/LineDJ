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

import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, TableHandler, TreeHandler}
import net.sf.jguiraffe.gui.builder.event.{FormChangeEvent, FormChangeListener}

object SelectionChangeHandler:
  /**
    * The name of the action that adds all songs of the currently selected
    * medium to the playlist.
    */
  final val AddMediumAction = "addMediumAction"

  /**
    * The name of the action that adds all songs of the currently selected
    * artist to the playlist.
    */
  final val AddArtistAction = "addArtistAction"

  /**
    * The name of the action that adds all songs of the currently selected
    * album in the artist tree to the playlist.
    */
  final val AddArtistAlbumAction = "addArtistAlbumAction"

  /**
    * The name of the action that adds all songs of the currently selected
    * album to the playlist.
    */
  final val AddAlbumAction = "addAlbumAction"

  /**
    * The name of the action that adds the currently selected songs in the
    * albums table to the playlist.
    */
  final val AddAlbumSongsAction = "addAlbumSongsAction"

  /**
    * The name of the action that adds the currently selected songs in the
    * artist album table to the playlist.
    */
  final val AddArtistAlbumSongsAction = "addArtistAlbumSongsAction"

  /** A list with the names of all actions for adding songs to the playlist. */
  private val AllAddActions = List(
    AddAlbumAction,
    AddAlbumSongsAction,
    AddArtistAction,
    AddArtistAlbumAction,
    AddArtistAlbumSongsAction,
    AddMediumAction
  )

  /** The name of the table with the albums of a medium. */
  private val TableAlbums = "tableAlbums"

  /** The name of the table with the songs of an album. */
  private val TableAlbumSongs = "tableAlbumSongs"

  /** The name of the table with the songs on the artist view. */
  private val TableArtistAlbumSongs = "tableArtistSongs"
end SelectionChangeHandler

/**
  * An event handler implementation that is registered at multiple UI
  * components. When it receives a selection change event, it propagates this
  * notification to the controller. For this purpose, it has to determine the
  * source of the change event and its semantic meaning. The selection status
  * also affects whether various actions to populate the playlist are enabled
  * or disabled. This class is also responsible for updating the action state
  * accordingly.
  *
  * @param controller  the controller
  * @param actionStore the action store
  */
class SelectionChangeHandler(controller: Controller, actionStore: ActionStore) extends FormChangeListener:

  import SelectionChangeHandler.*

  override def elementChanged(e: FormChangeEvent): Unit =
    e.getHandler match
      case list: ListComponentHandler =>
        val mediumSelection = Option(list.getData).map(_.asInstanceOf[Checksums.MediumChecksum])
        controller.mediumSelected(mediumSelection)
        if mediumSelection.isDefined then
          enableAction(AddMediumAction, enabled = true)
        else
          AllAddActions.foreach: action =>
            enableAction(action, enabled = false)

      case tree: TreeHandler =>
        val selection = Option(tree.getSelectedPath)
          .map(_.getTargetNode.getValue)
          .filter(_.isInstanceOf[Controller.SongOwnerID])
          .map(_.asInstanceOf[Controller.SongOwnerID])
        controller.artistAlbumSelected(selection)
        enableAction(AddArtistAction, selection.isDefined)
        enableAction(AddArtistAlbumAction, selection.exists(_.isInstanceOf[Controller.AlbumID]))

      case table: TableHandler =>
        e.getName match
          case TableAlbums =>
            val selection = table.getSelectedIndices
            val optAlbumID = if selection.length == 1 then
              val albumData = table.getModel.get(selection.head).asInstanceOf[AlbumData]
              enableAction(AddAlbumAction, enabled = true)
              Some(Controller.AlbumID(albumData.albumInfo.id))
            else
              enableAction(AddAlbumAction, enabled = false)
              None
            controller.albumSelected(optAlbumID)
          case TableAlbumSongs =>
            enableAddSongsAction(table, AddAlbumSongsAction)
          case TableArtistAlbumSongs =>
            enableAddSongsAction(table, AddArtistAlbumSongsAction)

  /**
    * Helper function to set the enabled state of a specific action.
    *
    * @param name    the name of the action
    * @param enabled the enabled flag
    */
  private def enableAction(name: String, enabled: Boolean): Unit =
    actionStore.getAction(name).setEnabled(enabled)

  /**
    * Enables or disable an action to add the selected songs of a table based
    * on the current table selection.
    *
    * @param table      the affected table
    * @param actionName the name of the action to modify
    */
  private def enableAddSongsAction(table: TableHandler, actionName: String): Unit =
    val selection = table.getSelectedIndices
    enableAction(actionName, selection.nonEmpty)
