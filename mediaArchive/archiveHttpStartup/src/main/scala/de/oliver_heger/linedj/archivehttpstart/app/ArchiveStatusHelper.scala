/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.archivehttpstart.app

import de.oliver_heger.linedj.archivehttp.{HttpArchiveStateFailedRequest, HttpArchiveStateServerError}
import de.oliver_heger.linedj.archivehttpstart.app.HttpArchiveStates._
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler
import net.sf.jguiraffe.resources.Message

/**
  * A class offering functionality related to the management of status
  * information for the HTTP archives.
  *
  * An instance of this class is used by the HTTP archive startup application.
  * It is able to produce correct status line texts based on the status of an
  * HTTP archive. This is needed to display detail information when the user
  * selects one of the managed archives in the overview UI.
  *
  * An instance also manages some status icons that are displayed in the
  * overview UI next to the managed archives in order to represent their
  * current state.
  *
  * @param appContext        the application context
  * @param handlerStatusLine the handler for the status line control
  * @param iconInactive      icon for the inactive state
  * @param iconActive        icon for the active state
  * @param iconPending       icon for the pending state (initializing)
  * @param iconLocked        icon to indicated that an encrypted archive is locked
  * @param iconUnlocked      icon to indicate that an encrypted archive is unlocked
  */
class ArchiveStatusHelper(appContext: ApplicationContext, handlerStatusLine: StaticTextHandler,
                          val iconInactive: AnyRef, val iconActive: AnyRef,
                          val iconPending: AnyRef, val iconLocked: AnyRef,
                          val iconUnlocked: AnyRef):
  /**
    * Displays a text in the status line that corresponds to the specified
    * archive state. This method is called when the user selects an archive in
    * the UI.
    *
    * @param state the state to be displayed in the status line
    */
  def updateStatusLine(state: HttpArchiveState): Unit =
    val msg = getStatusTextRes(state)
    val text = appContext getResourceText msg
    handlerStatusLine setText text

  /**
    * Clears the text which is currently displayed in the status line.
    */
  def clearStatusLine(): Unit =
    handlerStatusLine setText null

  /**
    * Returns the resource message for the text to be displayed in the status
    * line for the given archive state.
    *
    * @param state the archive state
    * @return the ''Message'' defining the text to be displayed
    */
  private def getStatusTextRes(state: HttpArchiveState): Message =
    state match
      case HttpArchiveStateInitializing =>
        new Message("state_initializing")
      case HttpArchiveStateAvailable =>
        new Message("state_active")
      case HttpArchiveStateNoUnionArchive =>
        new Message("state_no_archive")
      case HttpArchiveStateNotLoggedIn =>
        new Message("state_no_login")
      case HttpArchiveStateLocked =>
        new Message("state_locked")
      case HttpArchiveStateNoProtocol =>
        new Message("state_no_protocol")
      case HttpArchiveErrorState(HttpArchiveStateFailedRequest(status)) =>
        new Message(null, "state_failed_request", status.toString())
      case HttpArchiveErrorState(HttpArchiveStateServerError(ex)) =>
        new Message(null, "state_server_error", ex.getMessage)
      case HttpArchiveErrorState(_) =>
        new Message("state_invalid")
