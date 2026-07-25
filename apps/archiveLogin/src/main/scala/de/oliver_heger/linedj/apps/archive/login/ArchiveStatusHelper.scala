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

package de.oliver_heger.linedj.apps.archive.login

import de.oliver_heger.linedj.apps.archive.login.ArchiveStatusHelper.ArchiveState
import de.oliver_heger.linedj.platform.archiveclient.LoginService
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler
import net.sf.jguiraffe.resources.Message

object ArchiveStatusHelper:
  /**
    * An enumeration class defining possible states of cloud archives. This is
    * used to display correct graphical elements for the archive.
    */
  enum ArchiveState:
    /**
      * The _Waiting_ status. The archive has not yet been loaded, since not 
      * all required credentials are known.
      */
    case Waiting

    /**
      * The _Loaded_ status. The content of the archive has been loaded, and
      * its media files are available for download.
      */
    case Loaded

    /**
      * The _Failed_ status. Loading of the archive has been attempted, but 
      * failed. Some information about the failure is available in further
      * attributes.
      *
      * @param message  an error message describing the cause of the failure
      * @param attempts the number of attempts to load the archive
      */
    case Failed(message: String,
                attempts: Int)
  end ArchiveState

  /**
    * Returns an [[ArchiveState]] object for a specific archive based on the
    * provided archive state.
    *
    * @param archive      the name of the affected archive
    * @param archiveState the current archive state
    * @return the [[ArchiveState]] for this archive
    */
  def stateFor(archive: String, archiveState: LoginService.ArchiveLoginState): ArchiveState =
    archiveState.failedArchives.get(archive).map: failed =>
      ArchiveState.Failed(failed.failure, failed.attempts)
    .getOrElse:
      if archiveState.loadedArchives.contains(archive) then ArchiveState.Loaded
      else ArchiveState.Waiting

  /**
    * Returns a [[Message]] to set the status text for the given archive state.
    *
    * @param state the archive state
    * @return the [[Message]] for the status text
    */
  private def getStatusTextRes(state: ArchiveState): Message =
    state match
      case ArchiveState.Waiting =>
        Message("state_waiting")
      case ArchiveState.Loaded =>
        Message("state_loaded")
      case ArchiveState.Failed(message, attempts) =>
        Message(null, "state_failed", message, attempts)
end ArchiveStatusHelper

/**
  * A class offering functionality related to the management of status
  * information for cloud archives.
  *
  * The class manages the icons of the different archive states and can update
  * the status line for the currently selected archive.
  *
  * @param appContext        the application context
  * @param handlerStatusLine the handler for the status line control
  * @param iconWaiting       icon for an archive not yet loaded
  * @param iconLoaded        icon for a loaded archive
  * @param iconError         icon for an archive in error state
  */
class ArchiveStatusHelper(appContext: ApplicationContext,
                          handlerStatusLine: StaticTextHandler,
                          val iconWaiting: AnyRef,
                          val iconLoaded: AnyRef,
                          val iconError: AnyRef):

  import ArchiveStatusHelper.*

  /**
    * Returns the icon to represent an archive with the given state.
    *
    * @param state the state in question
    * @return the icon to be displayed for this state
    */
  def iconForState(state: ArchiveStatusHelper.ArchiveState): AnyRef =
    state match
      case ArchiveState.Waiting => iconWaiting
      case ArchiveState.Loaded => iconLoaded
      case ArchiveState.Failed(_, _) => iconError

  /**
    * Displays a text in the status line that corresponds to the specified
    * archive state. This method is called when the user selects an archive in
    * the UI.
    *
    * @param state the state to be displayed in the status line
    */
  def updateStatusLine(state: ArchiveState): Unit =
    val msg = getStatusTextRes(state)
    val text = appContext.getResourceText(msg)
    handlerStatusLine.setText(text)

  /**
    * Clears the text which is currently displayed in the status line.
    */
  def clearStatusLine(): Unit =
    handlerStatusLine.setText(null)
