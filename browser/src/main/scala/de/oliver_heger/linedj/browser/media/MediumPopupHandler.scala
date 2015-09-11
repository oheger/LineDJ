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

import de.oliver_heger.linedj.browser.model.{SongData, AppendSongs}
import de.oliver_heger.linedj.remoting.MessageBus
import net.sf.jguiraffe.gui.builder.action._
import net.sf.jguiraffe.gui.builder.components.ComponentBuilderData
import net.sf.jguiraffe.gui.builder.components.model.TableHandler

object MediumPopupHandler {
  /** The name of the bean for the medium controller. */
  private val BeanController = "mediaController"

  /** The name of the action for adding the current medium. */
  private val ActionAddMedium = "addMediumAction"

  /**
   * The name of the action for adding the songs of the currently selected
   * artists.
   */
  private val ActionAddArtist = "addArtistAction"

  /**
   * The name of the action for adding the songs of the currently selected
   * albums.
   */
  private val ActionAddAlbum = "addAlbumAction"

  /**
   * The name of the action for adding the currently selected songs.
   */
  private val ActionAddSongs = "addSongsAction"

  /** The name of the media table component. */
  private val CompTable = "tableMedia"
}

/**
 * A handler for creating popup menus for the medium page.
 *
 * This handler generates popup menus for some of the controls of the medium
 * page. The menus contain actions for adding parts of the current medium to
 * the playlist under construction. For instance, it is possible to add all
 * songs of currently selected albums or artists.
 *
 * @param messageBus the message bus
 */
class MediumPopupHandler(messageBus: MessageBus) extends PopupMenuHandler {

  import MediumPopupHandler._

  override def constructPopup(builder: PopupMenuBuilder, compData: ComponentBuilderData): Unit = {
    def bean(name: String): Any = compData.getBeanContext getBean name

    val actionStore = bean(ActionBuilder.KEY_ACTION_STORE).asInstanceOf[ActionStore]
    lazy val controller = bean(BeanController).asInstanceOf[MediaController]

    def fetchAndPrepareAction(name: String)(f: MediaController => Seq[SongData]): FormAction = {
      val action = actionStore getAction name
      action setTask createActionTask(f(controller))
      action
    }

    builder addAction fetchAndPrepareAction(ActionAddMedium)(_.songsForSelectedMedium)
    builder addAction fetchAndPrepareAction(ActionAddArtist)(_.songsForSelectedArtists)
    builder addAction fetchAndPrepareAction(ActionAddAlbum)(_.songsForSelectedAlbums)

    handleSelectedSongs(builder, compData, actionStore)
    builder.create()
  }

  /**
   * Adds an action to add the currently selected songs in the media table if
   * a selection exists.
   * @param builder the popup menu builder
   * @param compData the component builder data
   * @param actionStore the action store
   * @return the modified popup builder
   */
  private def handleSelectedSongs(builder: PopupMenuBuilder, compData: ComponentBuilderData,
                                  actionStore: ActionStore): PopupMenuBuilder = {
    val tableHandler = compData.getComponentHandler(CompTable).asInstanceOf[TableHandler]
    val selectedIndices = tableHandler.getSelectedIndices
    if (selectedIndices.nonEmpty) {
      builder.addSeparator()
      val songs = selectedIndices map (tableHandler.getModel.get(_).asInstanceOf[SongData])
      val action = actionStore getAction ActionAddSongs
      action setTask createActionTask(songs)
      builder addAction action
    }
    builder
  }

  /**
   * Creates a task for an action which adds the specified songs to the
   * playlist under construction.
   * @param songs the songs to be added
   * @return the action task
   */
  private def createActionTask(songs: => Seq[SongData]): Runnable =
    new Runnable {
      override def run(): Unit = {
        messageBus publish AppendSongs(songs)
      }
    }
}
