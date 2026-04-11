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

package de.oliver_heger.linedj.archive.server.cloud

import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

/**
  * An object defining model classes that are used by the endpoints specific to
  * the cloud archive server.
  */
object CloudArchiveModel:
  /**
    * A data class that stores information about the credentials that can be
    * set to unlock cloud archives. An instance stores the names of pending
    * credentials grouped by their types.
    *
    * @param fileCredentials    the names of credentials to decrypt files with
    *                           further credentials
    * @param archiveCredentials the names of credentials related to archives
    */
  final case class CredentialsInfo(fileCredentials: Set[String],
                                   archiveCredentials: Set[String])

  /**
    * A trait providing JSON serialization support for the data classes 
    * defined in this model.
    */
  trait CloudArchiveJsonSupport extends SprayJsonSupport, DefaultJsonProtocol:
    given credentialsInfoFormat: RootJsonFormat[CredentialsInfo] = jsonFormat2(CredentialsInfo.apply)
    