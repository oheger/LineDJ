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

import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, HttpRequestSenderFactoryImpl, Spawner}
import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.model.headers.{ETag, `If-None-Match`}
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.util.Timeout

import scala.concurrent.{ExecutionContext, Future}

private object ArchiveServiceImpl extends ArchiveModel.ArchiveJsonSupport:
  /** The name that is used for the request sender actor. */
  final val SenderName = "archiveServerClient"

  /** The name of the archive monitor actor. */
  final val MonitorName = "archiveMonitor"

  /** The request to query media information from the archive. */
  private val MediaRequest = HttpRequest(uri = "/api/archive/media")

  /** The logger. */
  private val log = LogManager.getLogger(ArchiveServiceImpl.getClass)

  /**
    * A factory trait for creating new instances of [[ArchiveServiceImpl]].
    */
  trait Factory:
    /**
      * Creates a new [[ArchiveServiceImpl]] instance to interact with the
      * archive server at the given URI.
      *
      * @param uri                      the URI of the archive server
      * @param optContentMonitorBackoff params to monitor the archive
      * @param senderFactory            the factory to create the sender actor
      * @param monitorFactory           the factory to create the monitor actor
      * @param system                   the actor system
      * @param timeout                  a timeout for sending requests
      * @return the newly created instance
      */
    def apply(uri: String,
              optContentMonitorBackoff: Option[BackoffConfig],
              senderFactory: HttpRequestSenderFactory = HttpRequestSenderFactoryImpl,
              monitorFactory: ArchiveStateMonitor.Factory = ArchiveStateMonitor.newInstance)
             (using system: classic.ActorSystem, timeout: Timeout): ArchiveServiceImpl

  /**
    * A default [[Factory]] instance for creating a new service instance.
    */
  final val newInstance: Factory = new Factory:
    override def apply(uri: String,
                       optContentMonitorBackoff: Option[BackoffConfig],
                       senderFactory: HttpRequestSenderFactory,
                       monitorFactory: ArchiveStateMonitor.Factory)
                      (using system: classic.ActorSystem, timeout: Timeout): ArchiveServiceImpl =
      val senderConfig = HttpRequestSenderConfig(
        actorName = Some(SenderName)
      )
      val spawner: Spawner = system
      val sender = senderFactory.createRequestSender(spawner, uri, senderConfig)
      val optMonitor = optContentMonitorBackoff.map: backoff =>
        val monitorParams = ArchiveStateMonitor.Params(
          archiveSender = sender,
          backoffConfig = backoff,
          requestTimeout = timeout,
          requestFunc = createRequest,
          evaluateFunc = evaluateResponse
        )
        monitorFactory(monitorParams, MonitorName)

      new ArchiveServiceImpl(sender, optMonitor, system)

  /**
    * The function to generate the request for the monitor actor. The managed
    * data is already the request with a proper if-none-match header.
    *
    * @param optData the optional managed data
    * @return the request to send to the archive
    */
  private def createRequest(optData: Option[HttpRequest]): HttpRequest =
    optData.getOrElse(MediaRequest)

  /**
    * Returns the function to evaluate the response received from the archive
    * server. The function checks whether new media data is available.
    *
    * @param system the implicit actor system
    * @return the evaluate function
    */
  private def evaluateResponse(using system: classic.ActorSystem):
  ArchiveStateMonitor.EvaluateFunc[HttpRequest, ArchiveModel.MediaOverview] =
    given ExecutionContext = system.dispatcher

    (response, optData) =>
      if response.status != StatusCodes.NotModified then
        Unmarshal(response).to[ArchiveModel.MediaOverview] map : media =>
          val nextRequest = response.header[ETag].map: etag =>
            MediaRequest.withHeaders(Seq(`If-None-Match`(etag.etag)))
          .getOrElse(MediaRequest)
          Some(nextRequest, media)
      else
        Future.successful(None)
end ArchiveServiceImpl

/**
  * A default implementation of the [[ArchiveService]] trait. This
  * implementation uses an [[HttpRequestSender]] actor to communicate with the
  * archive server. This actor is created by the factory. It is released by the
  * ''close()'' function which should be called when the service is no longer
  * needed.
  *
  * @param requestSender   the actor for sending requests
  * @param optMonitorActor the optional actor that monitors the archive
  * @param actorSystem     the actor system
  * @param timeout         a timeout for sending requests
  */
private class ArchiveServiceImpl(override val requestSender: ActorRef[HttpRequestSender.HttpCommand],
                                 override val optMonitorActor: Option[ActorRef[ArchiveStateMonitor.ArchiveListenerCommand[ArchiveModel.MediaOverview]]],
                                 override val actorSystem: classic.ActorSystem)
                                (using timeout: Timeout) extends ArchiveService, AutoCloseable:
  /** The implicit typed actor system. */
  private given ActorSystem[Nothing] = actorSystem.toTyped

  /** The implicit execution context. */
  private given ExecutionContext = actorSystem.dispatcher

  override def sendRequest(request: HttpRequest): Future[HttpResponse] =
    HttpRequestSender.sendRequestSuccess(requestSender, request).map(_.response)

  override def close(): Unit =
    requestSender ! HttpRequestSender.Stop
    optMonitorActor.foreach(_ ! ArchiveStateMonitor.ArchiveListenerCommand.Stop())
