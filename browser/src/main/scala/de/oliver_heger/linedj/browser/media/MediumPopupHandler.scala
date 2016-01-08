/*
 * Copyright 2015-2016 The Developers Team.
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

import net.sf.jguiraffe.gui.builder.action._
import net.sf.jguiraffe.gui.builder.components.ComponentBuilderData

object MediumPopupHandler {
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
}

/**
 * A handler for creating popup menus for the medium page.
 *
 * This handler generates popup menus for some of the controls of the medium
 * page. The menus contain actions for adding parts of the current medium to
 * the playlist under construction. For instance, it is possible to add all
 * songs of currently selected albums or artists.
 */
class MediumPopupHandler extends PopupMenuHandler {

  import MediumPopupHandler._

  override def constructPopup(builder: PopupMenuBuilder, compData: ComponentBuilderData): Unit = {
    val actionStore = compData.getBeanContext.getBean(ActionBuilder.KEY_ACTION_STORE)
      .asInstanceOf[ActionStore]
    builder addAction actionStore.getAction(ActionAddMedium)
    builder addAction actionStore.getAction(ActionAddArtist)
    builder addAction actionStore.getAction(ActionAddAlbum)

    val action = actionStore.getAction(ActionAddSongs)
    if (action.isEnabled) {
      builder.addSeparator()
      builder addAction action
    }
    builder.create()
  }
}
