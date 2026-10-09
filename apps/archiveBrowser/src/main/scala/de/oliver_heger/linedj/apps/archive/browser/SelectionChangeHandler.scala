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

import de.oliver_heger.linedj.platform.audio2.AudioPlayerState
import de.oliver_heger.linedj.platform.comm.MessageBusListener
import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.model.{ListComponentHandler, TableHandler, TreeHandler}
import net.sf.jguiraffe.gui.builder.event.{FormChangeEvent, FormChangeListener}
import org.apache.pekko.actor.Actor.Receive

import scala.compiletime.uninitialized

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

  /**
    * Type alias for a function that notifies the controller about changes in
    * the selection of relevant UI controls.
    */
  private type ControllerNotifyFunc = Controller => Unit

  /**
    * Constant for a [[ControllerNotifyFunc]] that does not send a
    * notification.
    */
  private val NoNotification: ControllerNotifyFunc = _ => ()
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
class SelectionChangeHandler(controller: Controller, actionStore: ActionStore)
  extends FormChangeListener, MessageBusListener:

  import SelectionChangeHandler.*

  /**
    * Stores the current state of the audio player. To correctly enable or
    * disable the actions for adding media files to the playlist, the closed
    * state of the playlist must be known.
    */
  private var playerState: AudioPlayerState = uninitialized

  /**
    * A map that stores the enabled states for all actions for adding songs to
    * the playlist. These states are computed based on the current selections
    * in the relevant UI controls. Whether an action is actually enabled or not
    * also depends on the current state of the playlist. If this is changed,
    * all actions have to be updated; so there states derived from the UI must
    * be recorded, because this information is not available when receiving a
    * player state update.
    */
  private var addActionStates = Map.empty[String, Boolean]

  override def elementChanged(e: FormChangeEvent): Unit =
    val (update, actionState) = handleChangeEvent(e)
    update(controller)
    addActionStates ++= actionState
    enableAddActions()

  /**
    * @inheritdoc
    * This implementation processes updates of the current playlist status to
    * find out whether the playlist is closed. The actions to append songs to
    * the playlist are only enabled if the playlist is still open.
    */
  override def receive: Receive =
    case state: AudioPlayerState =>
      playerState = state
      enableAddActions()

  /**
    * Handles an event that indicates a change in the selection of a relevant
    * UI element. For this event, the controller has to be notified, and the
    * enabled state of some actions may change. This function does not trigger
    * these actions itself, but returns a tuple with objects that allows the
    * caller to do this. The result is a tuple with the following elements:
    *  - A function to notify the controller about the changed selection.
    *  - A [[Map]] with updated states for the add actions based on the
    *    processing of the given change event. The result is then merged with
    *    the state map managed by this object, and the action states are
    *    actually set.
    *
    * @param e the event to handle
    * @return a map with updates for action states
    */
  private def handleChangeEvent(e: FormChangeEvent): (ControllerNotifyFunc, Map[String, Boolean]) =
    e.getHandler match
      case list: ListComponentHandler =>
        val mediumSelection = Option(list.getData).map(_.asInstanceOf[Checksums.MediumChecksum])
        val update: ControllerNotifyFunc = _.mediumSelected(mediumSelection)
        val actionStates = if mediumSelection.isDefined then
          Map(AddMediumAction -> true)
        else
          AllAddActions.map(_ -> false).toMap
        (update, actionStates)

      case tree: TreeHandler =>
        val selection = Option(tree.getSelectedPath)
          .map(_.getTargetNode.getValue)
          .filter(_.isInstanceOf[Controller.SongOwnerID])
          .map(_.asInstanceOf[Controller.SongOwnerID])
        val update: ControllerNotifyFunc = _.artistAlbumSelected(selection)
        val actionStates = Map(
          AddArtistAction -> selection.isDefined,
          AddArtistAlbumAction -> selection.exists(_.isInstanceOf[Controller.AlbumID])
        )
        (update, actionStates)

      case table: TableHandler =>
        e.getName match
          case TableAlbums =>
            val selection = table.getSelectedIndices
            val (optAlbumID, actionStates) = if selection.length == 1 then
              val albumData = table.getModel.get(selection.head).asInstanceOf[AlbumData]
              (Some(Controller.AlbumID(albumData.albumInfo.id)), Map(AddAlbumAction -> true))
            else
              (None, Map(AddAlbumAction -> false))
            val update: ControllerNotifyFunc = _.albumSelected(optAlbumID)
            (update, actionStates)
          case TableAlbumSongs =>
            (NoNotification, addSongsActionState(table, AddAlbumSongsAction))
          case TableArtistAlbumSongs =>
            (NoNotification, addSongsActionState(table, AddArtistAlbumSongsAction))

  /**
    * Enables or disables all actions for adding songs to the playlist based
    * on their recorded state.
    */
  private def enableAddActions(): Unit =
    AllAddActions.foreach: action =>
      enableAction(action, addActionStates.getOrElse(action, false))

  /**
    * Helper function to set the enabled state of a specific action.
    *
    * @param name    the name of the action
    * @param enabled the enabled flag
    */
  private def enableAction(name: String, enabled: Boolean): Unit =
    actionStore.getAction(name).setEnabled(enabled && !playerState.playlistClosed)

  /**
    * Returns the action state for an action to add the selected songs of a
    * table based on the current table selection.
    *
    * @param table      the affected table
    * @param actionName the name of the action to modify
    * @return a [[Map]] with the changed action state
    */
  private def addSongsActionState(table: TableHandler, actionName: String): Map[String, Boolean] =
    val selection = table.getSelectedIndices
    Map(actionName -> selection.nonEmpty)
