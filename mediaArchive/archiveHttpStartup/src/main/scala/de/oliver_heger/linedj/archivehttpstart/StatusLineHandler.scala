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

package de.oliver_heger.linedj.archivehttpstart

import de.oliver_heger.linedj.archivehttpstart.HttpArchiveStates._
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler

/**
  * A class managing the status line of the window for the HTTP archive startup
  * application.
  *
  * The status line shows information whether the connection to the HTTP
  * archive has been established, and data has been published to the union
  * archive. It consists of an icon with the active state and a message with
  * more details.
  *
  * The class gets the current state of the HTTP archive passed in. It has to
  * translate this state into corresponding updates of UI elements.
  *
  * @param handlerStatusLine     the handler for the status line control
  * @param txtStateInvalidConfig localized text for the invalid configuration
  *                              state
  * @param txtStateNoArchive     localized text for the union archive unavailable
  *                              state
  * @param txtStateLogout        localized text for the logout state
  * @param txtStateActive        localized text for the active state
  * @param iconInactive          icon for the inactive state
  * @param iconActive            icon for the active state
  */
class StatusLineHandler(handlerStatusLine: StaticTextHandler,
                        txtStateInvalidConfig: String,
                        txtStateNoArchive: String,
                        txtStateLogout: String, txtStateActive: String,
                        iconInactive: AnyRef, iconActive: AnyRef) {
  /** Mapping from HTTP archive states to corresponding texts. */
  private val stateTextMapping = createStateTextMapping()

  /**
    * Notifies this object that the state of the monitored HTTP archive has
    * changed. This method causes the UI to be updated accordingly.
    *
    * @param state the new state of the archive
    */
  def archiveStateChanged(state: HttpArchiveState): Unit = {
    handlerStatusLine.setText(stateTextMapping(state))
    val icon = if (state.isActive) iconActive else iconInactive
    handlerStatusLine setIcon icon
  }

  /**
    * Generates a map to obtain the text for a given HTTP archive state.
    *
    * @return the map with texts for archive states
    */
  private def createStateTextMapping(): Map[HttpArchiveState, String] =
    Map(HttpArchiveStateInvalidConfig -> txtStateInvalidConfig,
      HttpArchiveStateNoUnionArchive -> txtStateNoArchive,
      HttpArchiveStateNotLoggedIn -> txtStateLogout,
      HttpArchiveStateAvailable -> txtStateActive)
}
