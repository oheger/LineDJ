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

package de.oliver_heger.linedj.platform.archiveclient

import de.oliver_heger.linedj.archive.server.cloud.model.CloudArchiveModel

import scala.concurrent.Future

object LoginService:
  /**
    * A data class storing a number of properties to reflect the login state of
    * a cloud archive server. This combines the state of the single managed
    * cloud archives with information about pending credentials. Clients of the
    * login service are typically interested in all these properties. Thus, the
    * change listener interface uses this as state that can be monitored.
    *
    * @param waitingArchives    the names of archives in waiting state
    * @param loadedArchives     the names of archives in loaded state
    * @param failedArchives     a map associating the names of failed archives
    *                           with further information about the failure
    * @param fileCredentials    the credentials unlocking credential files
    * @param archiveCredentials single credentials to unlock archives
    */
  final case class ArchiveLoginState(waitingArchives: Set[String],
                                     loadedArchives: Set[String],
                                     failedArchives: Map[String, CloudArchiveModel.FailedArchive],
                                     fileCredentials: Set[String],
                                     archiveCredentials: Set[String])
end LoginService

/**
  * A trait to define the interface of a service which interacts with an 
  * archive server that supports login operations.
  *
  * Via the functions provided by this service, clients can query the current
  * status of cloud archive, the credentials not yet available, and perform
  * login operations.
  */
trait LoginService extends MonitorSupport[LoginService.ArchiveLoginState]:
  /**
    * Returns a [[Future]] with information about the currently missing
    * credentials. This can be used by clients to figure out which credentials
    * can be queried from the user to unlock cloud archives.
    *
    * @return a [[Future]] with information about credentials
    */
  def credentialsInfo: Future[CloudArchiveModel.CredentialsInfo]

  /**
    * Returns a [[Future]] with information about the current login status of
    * the cloud archives managed by the connected server. This can be used by
    * clients to render an overview over the available cloud archives and which
    * of them can be accessed.
    *
    * @return a [[Future]] with information about the cloud archive state
    */
  def cloudArchiveState: Future[CloudArchiveModel.CloudArchiveStateResponse]

  /**
    * Allows setting credentials to unlock cloud archives. The provided map is
    * interpreted as the names of credentials and their values. The function
    * sends the provided credentials to the server and receives the updated
    * credentials state.
    *
    * @param credentials a map with the credentials to set
    * @return a [[Future]] with the updated credentials state
    */
  def setCredentials(credentials: Map[String, String]): Future[CloudArchiveModel.CredentialsInfo]
  