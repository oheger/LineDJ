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

import de.oliver_heger.linedj.archivehttp.config.UserCredentials

/**
  * A message indicating the change of the login state for an HTTP archive.
  *
  * Messages of this type are sent by the [[HttpArchiveLoginController]] when
  * the user entered new login credentials or pressed the ''Logout'' button.
  * In the latter case, no credentials are available, and the ''Option'' is
  * ''None''.
  *
  * @param credentials the credentials for login into the HTTP archive
  */
case class LoginStateChanged(credentials: Option[UserCredentials])

/**
  * An object defining the possible states of an HTTP archive.
  */
object HttpArchiveStates {

  /**
    * Representation of the state of an HTTP archive.
    *
    * The different states determine whether an HTTP archive is currently
    * active (i.e. contributing to the central union archive). If not, from the
    * state name, a reason can be obtained.
    *
    * @param name     a unique name of this state
    * @param isActive a flag whether the archive is currently active
    */
  sealed abstract class HttpArchiveState(val name: String, val isActive: Boolean)

  /**
    * An object representing the HTTP archive state that the archive's
    * configuration is invalid. This is a fatal error which makes it
    * impossible to start the HTTP archive.
    */
  case object HttpArchiveStateInvalidConfig
    extends HttpArchiveState("InvalidConfig", isActive = false)

  /**
    * An object representing the HTTP archive state that the union archive is
    * not available. In this case, no HTTP archive is started because there is
    * nothing it can contribute to.
    */
  case object HttpArchiveStateNoUnionArchive
    extends HttpArchiveState("NoUnionArchive", isActive = false)

  /**
    * An object representing the HTTP archive state that no user credentials
    * are available to log into the HTTP archive. The archive is disabled for
    * this reason because no data can be loaded from it.
    */
  case object HttpArchiveStateNotLoggedIn
    extends HttpArchiveState("NotLoggedIn", isActive = false)

  /**
    * An object representing the HTTP archive state ''available''. In this
    * state the content of the HTTP archive can be accessed and has been
    * contributed to the central union archive.
    */
  case object HttpArchiveStateAvailable
    extends HttpArchiveState("Available", isActive = true)
}
