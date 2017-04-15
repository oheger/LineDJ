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
