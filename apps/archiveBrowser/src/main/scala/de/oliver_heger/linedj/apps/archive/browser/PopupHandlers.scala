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

import net.sf.jguiraffe.gui.builder.action.{ActionBuilder, ActionStore, PopupMenuBuilder, PopupMenuHandler}
import net.sf.jguiraffe.gui.builder.components.ComponentBuilderData

/**
  * A [[PopupMenuHandler]] implementation for the artist view. This handler
  * constructs a popup menu for the actions related to artists and their 
  * albums.
  */
class ArtistPopupHandler extends PopupMenuHandler:
  override def constructPopup(builder: PopupMenuBuilder, compData: ComponentBuilderData): Unit =
    val actionStore = compData.getBeanContext.getBean(ActionBuilder.KEY_ACTION_STORE).asInstanceOf[ActionStore]
    builder.addAction(actionStore.getAction(SelectionChangeHandler.AddMediumAction))
    builder.addAction(actionStore.getAction(SelectionChangeHandler.AddArtistAction))
    builder.addAction(actionStore.getAction(SelectionChangeHandler.AddArtistAlbumAction))

    val addSongsAction = actionStore.getAction(SelectionChangeHandler.AddArtistAlbumSongsAction)
    if addSongsAction.isEnabled then
      builder.addSeparator()
      builder.addAction(actionStore.getAction(SelectionChangeHandler.AddArtistAlbumSongsAction))

    builder.create()
