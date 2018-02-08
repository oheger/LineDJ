/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp

import akka.http.scaladsl.model.StatusCode

/**
  * A trait defining the current state an HTTP archive is in.
  *
  * This trait and its implementations can be used to find out whether a
  * successful connection could be established to the remote HTTP server. If
  * this was not the case, error information is available.
  */
sealed trait HttpArchiveState

/**
  * Object representing the initial disconnected state.
  *
  * This state is set when the archive actor has been created and no
  * connection to the remote HTTP server has been established yet.
  */
case object HttpArchiveStateDisconnected extends HttpArchiveState

/**
  * Object representing a successful state.
  *
  * The connection to the remote HTTP server could be established, and its
  * content file could be read. The archive is ready to serve requests.
  */
case object HttpArchiveStateConnected extends HttpArchiveState

/**
  * A class representing the archive state that the HTTP server could not be
  * contacted.
  *
  * @param exception the exception that occurred when contacting the server
  */
case class HttpArchiveStateServerError(exception: Throwable) extends HttpArchiveState

/**
  * A class representing the archive state that the request for the HTTP
  * archive's content file failed. The non-success status code can be queried
  * from an instance.
  *
  * @param status the status of the failed response
  */
case class HttpArchiveStateFailedRequest(status: StatusCode) extends HttpArchiveState

/**
  * A message processed by [[HttpArchiveManagementActor]] that allows
  * querying the current state of the archive.
  *
  * Using this message it can be found out whether the archive is ready to
  * serve requests or whether an error occurred when a connection to the HTTP
  * server was created.
  */
case object HttpArchiveStateRequest

/**
  * A message sent by [[HttpArchiveManagementActor]] in response to a
  * [[HttpArchiveStateRequest]] message.
  *
  * The message contains the current state the archive is in. To enable a
  * receiver to connect this message to a specific archive, the archive's name
  * (as defined in the configuration) is contained, too.
  *
  * @param archiveName the name of the sending archive
  * @param state       the state the archive is currently in
  */
case class HttpArchiveStateResponse(archiveName: String, state: HttpArchiveState)
