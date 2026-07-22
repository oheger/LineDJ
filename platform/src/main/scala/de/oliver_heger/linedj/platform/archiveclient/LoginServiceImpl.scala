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
import de.oliver_heger.linedj.platform.archiveclient.LoginServiceImpl.queryArchiveState
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.util.Timeout
import spray.json.enrichAny

import scala.concurrent.{ExecutionContext, Future}

private object LoginServiceImpl extends CloudArchiveModel.CloudArchiveJsonSupport:
  /**
    * Type alias for the DATA and STATE types for the monitor actor. For both
    * types the current cloud archive state is used. The request function does
    * not depend on any input; always the same request is sent.
    */
  private[archiveclient] type MonitorData = CloudArchiveModel.CloudArchiveStateResponse

  /**
    * The requests to be sent by the monitor actor to query the archive status.
    */
  private val ArchiveStatusRequests = List(HttpRequest(uri = "/api/archive/archives/status"))

  /** The request function for the monitor actor. */
  private val MonitorRequestFunc: ArchiveStateMonitor.RequestFunc[MonitorData] = _ => ArchiveStatusRequests

  /** The name of the monitor actor. */
  final val MonitorActorName = "archiveStateMonitor"

  /**
    * A factory trait for creating new instances of [[LoginServiceImpl]].
    */
  trait Factory:
    /**
      * Creates a new [[LoginServiceImpl]] instance to interact with the 
      * archive server wrapped by the given [[ArchiveService]].
      *
      * @param archiveService    the service to access the archive
      * @param optMonitorBackoff optional backoff config for a monitor actor
      * @param monitorFactory    the factory to create a monitor actor
      * @param system            the actor system
      * @param timeout           the timeout for the monitor actor
      * @return the newly created instance
      */
    def apply(archiveService: ArchiveService,
              optMonitorBackoff: Option[BackoffConfig],
              monitorFactory: ArchiveStateMonitor.Factory = ArchiveStateMonitor.newInstance)
             (using system: classic.ActorSystem, timeout: Timeout): LoginServiceImpl

  /** A default [[Factory]] instance to create new service instances. */
  final val newInstance: Factory =
    new Factory:
      override def apply(archiveService: ArchiveService,
                         optMonitorBackoff: Option[BackoffConfig],
                         monitorFactory: ArchiveStateMonitor.Factory = ArchiveStateMonitor.newInstance)
                        (using system: ActorSystem, timeout: Timeout): LoginServiceImpl =
        new LoginServiceImpl(archiveService, createMonitorActor(archiveService, optMonitorBackoff, monitorFactory))

  /**
    * Queries the current archive login state using the given archive service.
    *
    * @param archiveService the archive service
    * @return a [[Future]] with the archive login state
    */
  private[archiveclient] def queryArchiveState(archiveService: ArchiveService):
  Future[CloudArchiveModel.CloudArchiveStateResponse] =
    archiveService.queryData("/api/archive/archives/status")

  /**
    * Returns the evaluate function for the monitor actor. The managed data is
    * extracted from the response entity. It is only returned if it has changed
    * from the last response.
    *
    * @param system the implicit actor system
    * @return the function to evaluate the monitor response
    */
  private def evaluateResponse(using system: classic.ActorSystem):
  ArchiveStateMonitor.EvaluateFunc[MonitorData, MonitorData] =
    given ExecutionContext = system.dispatcher

    (responses, optData) =>
      val response = responses.head
      Unmarshal(response).to[MonitorData] map : state =>
        if optData.contains(state) then
          None
        else
          Some((state, state))

  /**
    * Creates the monitor actor if a corresponding backoff configuration is
    * provided.
    *
    * @param archiveService    the archive service
    * @param optMonitorBackoff the optional monitor backoff config
    * @param monitorFactory    the monitor factory
    * @param system            the actor system
    * @param timeout           the timeout for monitor requests
    * @return an [[Option]] with the monitor actor
    */
  private def createMonitorActor(archiveService: ArchiveService,
                                 optMonitorBackoff: Option[BackoffConfig],
                                 monitorFactory: ArchiveStateMonitor.Factory)
                                (using system: ActorSystem, timeout: Timeout):
  Option[ActorRef[ArchiveStateMonitor.ArchiveListenerCommand[MonitorData]]] =
    optMonitorBackoff map : backoff =>
      val params = ArchiveStateMonitor.Params(
        archiveSender = archiveService.requestSender,
        backoffConfig = backoff,
        requestFunc = MonitorRequestFunc,
        evaluateFunc = evaluateResponse,
        requestTimeout = timeout
      )
      monitorFactory(params, MonitorActorName)
end LoginServiceImpl

/**
  * The implementation of the [[LoginService]] trait.
  *
  * This implementation uses an [[ArchiveService]] instance to interact with an
  * archive server. It provides a wrapper around the REST API.
  *
  * @param archiveService the service to interact with the archive server
  */
private class LoginServiceImpl(archiveService: ArchiveService,
                               override val optMonitorActor: Option[ActorRef[
                                 ArchiveStateMonitor.ArchiveListenerCommand[LoginServiceImpl.MonitorData]]])
                              (using system: classic.ActorSystem) extends LoginService, AutoCloseable,
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
    queryArchiveState(archiveService)

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

  /**
    * @inheritdoc This implementation closes the monitor actor if it exists.
    */
  override def close(): Unit =
    optMonitorActor.foreach(_ ! ArchiveStateMonitor.ArchiveListenerCommand.Stop())
