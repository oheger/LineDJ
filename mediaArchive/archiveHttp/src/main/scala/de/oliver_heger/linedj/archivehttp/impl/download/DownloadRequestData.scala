/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.impl.download

import de.oliver_heger.linedj.shared.archive.media.DownloadData
import org.apache.pekko.actor.ActorRef

import java.nio.file.Path

/**
  * An internally used data class to record a request for data.
  *
  * This class is used by multiple implementation classes of the download
  * functionality to keep track about requests that have to be stored because
  * they cannot be fulfilled immediately.
  *
  * @param request the request
  * @param client  the client actor
  */
private case class DownloadRequestData(request: DownloadData, client: ActorRef)

/**
  * Stores information about a read operation of a temporary file.
  *
  * @param reader the reader actor used to read the file
  * @param path   the path of the temporary file
  */
case class TempReadOperation(reader: ActorRef, path: Path)

/**
  * An internally used class that holds the information to react on a download
  * complete message from a reader actor for a temporary file.
  *
  * At that time, the read operation must be known, so that the reader actor
  * can be stopped, and the temporary file can be removed. If a request is
  * currently pending, it is in the responsibility of the caller to handle it.
  *
  * @param operation      information about the completed operation
  * @param pendingRequest an option for a pending request
  */
private case class CompletedTempReadOperation(operation: TempReadOperation,
                                              pendingRequest: Option[DownloadRequestData])
