/*
 * Copyright 2015-2022 The Developers Team.
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

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Source
import akka.util.ByteString
import de.oliver_heger.linedj.archivecommon.download.MediaFileDownloadActor
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.archivehttp.impl.download.HttpDownloadActor.DownloadRequestCompleted
import de.oliver_heger.linedj.shared.archive.media.{DownloadData, DownloadDataResult, MediumFileRequest}
import de.oliver_heger.linedj.utils.ChildActorFactory

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

private object HttpDownloadActor {
  /**
    * Returns ''Props'' to create a new instance of this actor.
    *
    * @param config          the ''HttpArchiveConfig''
    * @param downloadRequest the download request to process
    * @param transformFunc   the transformation function for the download actor
    * @return ''Props'' to create a new actor instance
    */
  def apply(config: HttpArchiveConfig,
            downloadRequest: MediumFileRequest,
            transformFunc: MediaFileDownloadActor.DownloadTransformFunc): Props =
    Props(classOf[HttpDownloadActorImpl], config, downloadRequest, transformFunc)

  private class HttpDownloadActorImpl(config: HttpArchiveConfig,
                                      downloadRequest: MediumFileRequest,
                                      transformFunc: MediaFileDownloadActor.DownloadTransformFunc)
    extends HttpDownloadActor(config, downloadRequest, transformFunc) with ChildActorFactory

  /**
    * An internal message the actor sends to itself when the download of the
    * requested media file has produced a result.
    *
    * @param triedData      the tried data source for the downloaded file
    * @param initialRequest the initial request for a chunk of data
    * @param client         the sender of the request
    */
  private case class DownloadRequestCompleted(triedData: Try[Source[ByteString, Any]],
                                              initialRequest: DownloadData,
                                              client: ActorRef)
}

/**
  * An internally used helper actor class that allows downloading a specific
  * media file.
  *
  * This class wraps an [[HttpFileDownloadActor]]. On receiving the first
  * request for data, it uses the downloader from the configuration to execute
  * the request for the media file. With the result, it creates the actual
  * downloader actor and forwards data requests to it.
  *
  * The main purpose of this class is to shield clients from the complexity of
  * sending a download request, waiting for the asynchronous response, and
  * instantiate an actor with the proper ''Source''. Using this actor, an actor
  * reference is available immediately and can be used for sending messages.
  *
  * @param config          the ''HttpArchiveConfig''
  * @param downloadRequest the download request to process
  * @param transformFunc   the transformation function for the download actor
  */
private class HttpDownloadActor(config: HttpArchiveConfig,
                                downloadRequest: MediumFileRequest,
                                transformFunc: MediaFileDownloadActor.DownloadTransformFunc)
  extends Actor with ActorLogging {
  this: ChildActorFactory =>
  override def receive: Receive = {
    case request: DownloadData =>
      implicit val ec: ExecutionContext = context.dispatcher
      val client = sender()
      context.become(waitForResponse)
      log.info("Sending download request for {}.", downloadRequest.fileID)
      config.downloader.downloadMediaFile(config.mediaPath, downloadRequest.fileID.uri) onComplete { result =>
        self ! DownloadRequestCompleted(result, request, client)
      }
  }

  /**
    * Returns a message handler function that is active while the download of
    * the requested media file is in progress. As soon as the data source is
    * available, the wrapped download actor is created.
    *
    * @return the handler function to wait for the completed download
    */
  private def waitForResponse: Receive = {
    case DownloadRequestCompleted(triedData, request, client) =>
      triedData match {
        case Success(source) =>
          val downloadActor =
            createChildActor(HttpFileDownloadActor(source, Uri(downloadRequest.fileID.uri), transformFunc))
          context.become(downloadActive(downloadActor))
          context watch downloadActor
          self.tell(request, client)

        case Failure(exception) =>
          log.error(exception, "Download failed for {}.", downloadRequest.fileID)
          context.stop(self)
      }

    case _: DownloadData =>
      sender() ! DownloadDataResult(ByteString.empty)
  }

  /**
    * Returns a message handler function that controls the actual download
    * process. The function forwards requests to the managed download actor and
    * watches it for termination.
    *
    * @param downloadActor the managed download actor
    * @return the handler function controlling the download
    */
  private def downloadActive(downloadActor: ActorRef): Receive = {
    case downloadRequest: DownloadData =>
      downloadActor forward downloadRequest

    case Terminated(_) =>
      log.error("Managed HttpFileDownloadActor terminated.")
      context stop self
  }
}
