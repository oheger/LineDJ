/*
 * Copyright 2015-2021 The Developers Team.
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

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import de.oliver_heger.linedj.archivecommon.download.DownloadMonitoringActor.DownloadOperationStarted
import de.oliver_heger.linedj.archivecommon.download.MediaFileDownloadActor
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.archivehttp.temp.TempPathGenerator
import de.oliver_heger.linedj.extract.id3.processor.ID3v2ProcessingStage
import de.oliver_heger.linedj.shared.archive.media.{MediumFileRequest, MediumFileResponse}
import de.oliver_heger.linedj.utils.ChildActorFactory

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object HttpDownloadManagementActor {

  /**
    * An internally used data class to store information about a request to
    * download a file from an HTTP archive.
    *
    * @param request the actual request for the file
    * @param client  the requesting client
    */
  case class DownloadOperationRequest(request: MediumFileRequest, client: ActorRef)

  /**
    * Returns a ''Props'' instance for creating an actor instance of this
    * class.
    *
    * @param config          the configuration for the HTTP archive
    * @param pathGenerator   the object for generating temp paths
    * @param monitoringActor the download monitoring actor
    * @param removeActor     the actor to remove temporary files
    * @return ''Props'' to create a new actor instance
    */
  def apply(config: HttpArchiveConfig, pathGenerator: TempPathGenerator,
            monitoringActor: ActorRef, removeActor: ActorRef): Props =
    Props(classOf[HttpDownloadManagementActorImpl], config, pathGenerator, monitoringActor,
      removeActor)

  private class HttpDownloadManagementActorImpl(config: HttpArchiveConfig,
                                                pathGenerator: TempPathGenerator,
                                                monitoringActor: ActorRef,
                                                removeActor: ActorRef)
    extends HttpDownloadManagementActor(config, pathGenerator, monitoringActor, removeActor)
      with ChildActorFactory

  /**
    * A message class this actor sends to itself when a response for a download
    * request is returned from the downloader. Then all information is
    * available to send an answer to the client actor.
    *
    * @param request the original download request
    * @param data    the source with the data to be downloaded
    */
  private case class ProcessDownloadRequest(request: DownloadOperationRequest,
                                            data: Source[ByteString, Any])

  /**
    * The transformation function to remove meta data from a file to be
    * downloaded.
    *
    * @return the download transformation function
    */
  private val DropMetaDataTransformationFunc: MediaFileDownloadActor.DownloadTransformFunc = {
    case s if s matches "(?i)mp3" =>
      new ID3v2ProcessingStage(None)
  }

  /**
    * Returns the transformation function for a download operation based on
    * the specified request.
    *
    * @param request the download request
    * @return the transformation function to be used
    */
  private def downloadTransformationFunc(request: MediumFileRequest):
  MediaFileDownloadActor.DownloadTransformFunc =
    if (request.withMetaData) MediaFileDownloadActor.IdentityTransform
    else DropMetaDataTransformationFunc
}

/**
  * An actor class that manages download operations from an HTTP archive.
  *
  * This actor class processes download requests for files hosted by the
  * archive. When such a request arrives it delegates to the HTTP protocol to
  * actually send a request to the archive for the media file in question.
  * On receiving a success response, it creates an [[HttpFileDownloadActor]] to
  * read the data from the archive and wraps it in a
  * [[TimeoutAwareHttpDownloadActor]]. This actor is then passed to the
  * requesting client.
  *
  * Ongoing download operations are monitored by a monitoring actor, so that
  * pending actor references will eventually be stopped.
  *
  * @param config          the configuration for the HTTP archive
  * @param pathGenerator   an object to generate temporary paths
  * @param monitoringActor the actor to monitor download operations
  * @param removeActor     the actor to remove temporary files
  */
class HttpDownloadManagementActor(config: HttpArchiveConfig, pathGenerator: TempPathGenerator,
                                  monitoringActor: ActorRef, removeActor: ActorRef)
  extends Actor with ActorLogging {
  this: ChildActorFactory =>

  import HttpDownloadManagementActor._

  /**
    * Returns the actor system in implicit scope. This is needed to materialize
    * streams.
    *
    * @return the implicit actor system
    */
  implicit private def system: ActorSystem = context.system

  /** The timeout when sending a download request. */
  implicit private val requestTimeout: Timeout = config.processorTimeout

  import context.dispatcher

  /** A counter for download operations. */
  private var downloadIndex = 0

  override def receive: Receive = {
    case req: MediumFileRequest =>
      triggerFileDownload(req)

    case ProcessDownloadRequest(request, data) =>
      processSuccessResponse(request, data)
  }

  /**
    * Requests a file to be downloaded from the HTTP archive. When the
    * response arrives, further steps are triggered to process it.
    *
    * @param req the request for the file to be downloaded
    */
  private def triggerFileDownload(req: MediumFileRequest): Unit = {
    log.info("Sending request for file {}.", req.fileID.uri)
    val downloadOp = DownloadOperationRequest(req, sender())

    val futUri = Future.fromTry(Try(Uri(req.fileID.uri)))
    futUri flatMap config.downloader.downloadMediaFile onComplete {
      case Success(data) =>
        self ! ProcessDownloadRequest(request = downloadOp, data)
      case Failure(exception) =>
        log.error(exception, "Download request for {} failed!", req.fileID.uri)
        downloadOp.client ! MediumFileResponse(req, None, -1)
    }
  }

  /**
    * Processes a successful response for a request to download a file.
    * Download actors are created and sent to the client actor.
    *
    * @param request the download request to be handled
    * @param data    the source with the data to be downloaded
    */
  private def processSuccessResponse(request: DownloadOperationRequest, data: Source[ByteString, Any]):
  Unit = {
    downloadIndex += 1
    log.debug("Starting download operation {} after receiving successful response.",
      downloadIndex)
    val fileDownloadActor = createChildActor(HttpFileDownloadActor(data,
      Uri(request.request.fileID.uri), downloadTransformationFunc(request.request)))
    val timeoutActor = createChildActor(TimeoutAwareHttpDownloadActor(config, monitoringActor,
      fileDownloadActor, pathGenerator, removeActor, downloadIndex))
    monitoringActor ! DownloadOperationStarted(downloadActor = timeoutActor,
      client = request.client)
    request.client ! MediumFileResponse(request.request, Some(timeoutActor), 0)
  }
}
