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
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import spray.json.enrichAny

import scala.concurrent.{ExecutionContext, Future}

private object LoginServiceImpl:
  /**
    * A factory trait for creating new instances of [[LoginServiceImpl]].
    */
  trait Factory:
    /**
      * Creates a new [[LoginServiceImpl]] instance to interact with the 
      * archive server wrapped by the given [[ArchiveService]].
      *
      * @param archiveService the service to access the archive
      * @param system         the actor system
      * @return the newly created instance
      */
    def apply(archiveService: ArchiveService)
             (using system: classic.ActorSystem): LoginServiceImpl

  /** A default [[Factory]] instance to create new service instances. */
  final val newInstance: Factory =
    new Factory:
      override def apply(archiveService: ArchiveService)(using system: ActorSystem): LoginServiceImpl =
        new LoginServiceImpl(archiveService)
end LoginServiceImpl

/**
  * The implementation of the [[LoginService]] trait.
  *
  * This implementation uses an [[ArchiveService]] instance to interact with an
  * archive server. It provides a wrapper around the REST API.
  *
  * @param archiveService the service to interact with the archive server
  */
private class LoginServiceImpl(archiveService: ArchiveService)
                              (using system: classic.ActorSystem) extends LoginService,
  CloudArchiveModel.CloudArchiveJsonSupport:
  /** The implicit execution context. */
  private given ExecutionContext = system.dispatcher

  /**
    * Returns a [[Future]] with information about the currently missing
    * credentials. This can be used by clients to figure out which credentials
    * can be queried from the user to unlock cloud archives.
    *
    * @return a [[Future]] with information about credentials
    */
  override def credentialsInfo: Future[CloudArchiveModel.CredentialsInfo] =
    archiveService.queryData("/api/archive/credentials")

  /**
    * Returns a [[Future]] with information about the current login status of
    * the cloud archives managed by the connected server. This can be used by
    * clients to render an overview over the available cloud archives and which
    * of them can be accessed.
    *
    * @return a [[Future]] with information about the cloud archive state
    */
  override def cloudArchiveState: Future[CloudArchiveModel.CloudArchiveStateResponse] =
    archiveService.queryData("/api/archive/archives/status")

  /**
    * Allows setting credentials to unlock cloud archives. The provided map is
    * interpreted as the names of credentials and their values. The function
    * sends the provided credentials to the server and receives the updated
    * credentials state.
    *
    * @param credentials a map with the credentials to set
    * @return a [[Future]] with the updated credentials state
    */
  override def setCredentials(credentials: Map[String, String]): Future[CloudArchiveModel.CredentialsInfo] =
    val credentialsJson = credentials.toJson.compactPrint
    val entity = HttpEntity(ContentTypes.`application/json`, credentialsJson)
    val request = HttpRequest(
      uri = "/api/archive/credentials",
      method = HttpMethods.PUT,
      entity = entity
    )
    archiveService.sendRequest(request) flatMap : response =>
      Unmarshal(response).to[CloudArchiveModel.CredentialsInfo]

  override protected def optMonitorActor:
  Option[ActorRef[ArchiveStateMonitor.ArchiveListenerCommand[CloudArchiveModel.CloudArchiveStateResponse]]] = None
