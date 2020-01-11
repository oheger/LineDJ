/*
 * Copyright 2015-2020 The Developers Team.
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

import java.security.Key

import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.archivehttpstart.HttpArchiveStates.HttpArchiveState

/**
  * A message indicating the change of the login state for a realm.
  *
  * Messages of this type are sent by the [[HttpArchiveLoginDlgController]] when
  * the user entered new login credentials or pressed the ''Logout'' button.
  * In the latter case, no credentials are available, and the ''Option'' is
  * ''None''.
  *
  * @param realm       the realm affected by this change
  * @param credentials the credentials for login into the HTTP archive
  */
case class LoginStateChanged(realm: String, credentials: Option[UserCredentials])

/**
  * A message indicating the change of the lock state of an archive.
  *
  * Messages of this type are sent for encrypted archives when the user has
  * entered a password to unlock the archive or locks it again.
  *
  * @param archive the name of the archive affected
  * @param optKey  an option with the key to unlock the archive
  */
case class LockStateChanged(archive: String, optKey: Option[Key])

/**
  * An object defining the possible states of an HTTP archive.
  */
object HttpArchiveStates {

  /**
    * Representation of the state of an HTTP archive.
    *
    * The different states determine whether an HTTP archive is currently
    * started or active. (The difference is that an active archive is started
    * successfully and already contributes to the central union archive). If
    * not, from the state name, a reason can be obtained.
    *
    * @param name     a unique name of this state
    * @param isActive a flag whether the archive is currently active
    *                 @param isStarted flag whether the archive has been started
    */
  sealed abstract class HttpArchiveState(val name: String, val isActive: Boolean, val isStarted: Boolean)

  /**
    * An object representing the HTTP archive state that the union archive is
    * not available. In this case, no HTTP archive is started because there is
    * nothing it can contribute to.
    */
  case object HttpArchiveStateNoUnionArchive
    extends HttpArchiveState("NoUnionArchive", isActive = false, isStarted = false)

  /**
    * An object representing the HTTP archive state that the HTTP protocol
    * required for an archive is not available. The archive cannot be started,
    * as it cannot connect to the server.
    */
  case object HttpArchiveStateNoProtocol
    extends HttpArchiveState("NoProtocol", isActive = false, isStarted = false)

  /**
    * An object representing the HTTP archive state that no user credentials
    * are available to log into the HTTP archive. The archive is disabled for
    * this reason because no data can be loaded from it.
    */
  case object HttpArchiveStateNotLoggedIn
    extends HttpArchiveState("NotLoggedIn", isActive = false, isStarted = false)

  /**
    * An object representing the HTTP archive state that an encrypted HTTP
    * archive has not yet been unlocked. This means that the password to
    * decrypt the archive's content has not yet been entered.
    */
  case object HttpArchiveStateLocked
    extends HttpArchiveState("Locked", isActive = false, isStarted = false)

  /**
    * An object representing the HTTP archive state that the archive is
    * currently initializing itself. This is the state after the archive's
    * actors have been created. When initialization is complete a final state
    * will be entered.
    */
  case object HttpArchiveStateInitializing
    extends HttpArchiveState("Initializing", isActive = false, isStarted = true)

  /**
    * An object representing the HTTP archive state ''available''. In this
    * state the content of the HTTP archive can be accessed and has been
    * contributed to the central union archive.
    */
  case object HttpArchiveStateAvailable
    extends HttpArchiveState("Available", isActive = true, isStarted = true)

  /**
    * A class representing an error state of an HTTP archive. This state is
    * used if the initialization of the archive failed.
    *
    * @param state the state of the archive
    */
  case class HttpArchiveErrorState(state: de.oliver_heger.linedj.archivehttp.HttpArchiveState)
    extends HttpArchiveState("Error", isActive = false, isStarted = true)

}

/**
  * A message indicating a change of the state of an HTTP archive.
  *
  * The message contains the name of the archive affected and its new state.
  *
  * @param archiveName the archive name
  * @param state       the state of the archive
  */
case class HttpArchiveStateChanged(archiveName: String, state: HttpArchiveState)

/**
  * A message for requesting the current state of the HTTP archive.
  *
  * This message is sent by the HTTP archive login controller after the
  * main window has been opened. The application answers by publishing the
  * current archive state on the message bus. This is necessary because the
  * controller may miss some state change messages during initialization. By
  * sending a request explicitly, it can be sure that it receives the most
  * recent state.
  */
case object HttpArchiveStateRequest
