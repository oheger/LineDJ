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

import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.shared.actors.ActorFactory.executionContext
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}

import scala.concurrent.Future

/**
  * A trait defining the interface of a service to interact with an HTTP REST
  * server hosting a media archive.
  *
  * This service can be used to send requests to an archive server. This is the
  * basic functionality. In addition, there is functionality to monitor changes
  * in the media available in the archive.
  */
trait ArchiveService extends MonitorSupport[ArchiveModel.MediaOverview]:
  /**
    * Sends a request to the archive server wrapped by this service and returns
    * a [[Future]] with the response. A failure response returned by the server
    * is mapped to a failed [[Future]].
    *
    * @param request the request to be sent
    * @return a [[Future]] with the response
    */
  def sendRequest(request: HttpRequest): Future[HttpResponse]

  /**
    * A convenience function to send a GET request to the archive server which
    * is expected to return serialized JSON data of a specific type. The 
    * function automatically performs the de-serialization. Failure responses
    * from the server are again mapped to failed futures.
    *
    * @param uri          the URI to be requested
    * @param unmarshaller the object to de-serialize the result
    * @tparam A the type of result object
    * @return a [[Future]] with the resulting data object
    */
  def queryData[A](uri: String)(using unmarshaller: Unmarshaller[HttpResponse, A]): Future[A] =
    given ActorSystem = actorSystem

    for
      response <- sendRequest(HttpRequest(uri = uri))
      result <- Unmarshal(response).to[A]
    yield result

  /**
    * Returns an actor system to be used for interactions with actors and 
    * futures.
    *
    * @return the actor system
    */
  protected def actorSystem: ActorSystem 
  