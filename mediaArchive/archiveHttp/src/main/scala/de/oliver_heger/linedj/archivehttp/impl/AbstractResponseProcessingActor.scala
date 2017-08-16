/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.impl

import akka.actor.ActorRef
import akka.http.scaladsl.model.HttpResponse
import akka.stream.KillSwitch
import akka.stream.scaladsl.Source
import akka.util.ByteString
import de.oliver_heger.linedj.archivecommon.stream.StreamSizeRestrictionStage
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.io.stream.{AbstractStreamProcessingActor, CancelableStreamSupport}
import de.oliver_heger.linedj.shared.archive.media.MediumID

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object AbstractResponseProcessingActor {

  /**
    * An internally used message class that is sent when a stream completes.
    * In this case, the associated ''KillSwitch'' has to be removed.
    *
    * @param client       the client actor
    * @param result       the result of stream processing
    * @param killSwitchID the ID of the kill switch
    */
  private case class StreamCompleted(client: ActorRef, result: Any, killSwitchID: Int)

}

/**
  * An abstract base actor class for processing responses for meta data or
  * settings files received from an HTTP archive.
  *
  * A lot of functionality related to response handling is independent on the
  * concrete file type that has been requested. For instance, failed responses
  * have to be handled, support for cancellation has to be implemented,
  * processing results have to be sent to the calling actor, etc. This base
  * class implements this common functionality. Concrete subclasses mainly have
  * to deal with setting up a stream to process the content of the response
  * entity and to produce the results to be sent back to the caller.
  *
  * @param fileType a name for the file type processed by this actor; this is
  *                 used when generating error messages
  */
abstract class AbstractResponseProcessingActor(val fileType: String)
  extends AbstractStreamProcessingActor with CancelableStreamSupport {

  override def customReceive: Receive = {
    case ProcessResponse(mid, triedResponse, config, seqNo) =>
      handleHttpResponse(mid, triedResponse, config, seqNo)
  }

  /**
    * Processes the specified source with the content of the response entity
    * and returns a result and an object to cancel processing on an external
    * request. This method has to be implemented by concrete subclasses to
    * actually process the result of the requested file. When the returned
    * ''Future'' completes its result is sent to the calling actor. The
    * ''KillSwitch'' is stored temporarily, so that the stream can be canceled
    * if necessary.
    *
    * @param source the source to be processed
    * @param mid    the current ''MediumID''
    * @param config the archive configuration
    * @param seqNo  the sequence number of the current scan operation
    * @return a ''Future'' for the processing result and a ''KillSwitch''
    */
  protected def processSource(source: Source[ByteString, Any], mid: MediumID,
                              config: HttpArchiveConfig, seqNo: Int): (Future[Any], KillSwitch)

  /**
    * Creates the source for the stream of the response's data bytes.
    *
    * @param mid      the medium ID
    * @param response the response
    * @param config   the configuration of the HTTP archive
    * @return the source of the stream for the response's data bytes
    */
  private[impl] def createResponseDataSource(mid: MediumID, response: HttpResponse,
                                             config: HttpArchiveConfig):
  Source[ByteString, Any] =
    response.entity.dataBytes
      .via(new StreamSizeRestrictionStage(config.maxContentSize * 1024))

  /**
    * Handle a HTTP response for a meta data file. Checks whether the response
    * is successful. If so, its entity is parsed and converted. Otherwise, a
    * failure message is sent to the sending actor.
    *
    * @param mid           the medium ID
    * @param triedResponse a ''Try'' for the HTTP response
    * @param config        the HTTP archive configuration
    * @param seqNo         the current sequence number
    */
  private def handleHttpResponse(mid: MediumID, triedResponse: Try[HttpResponse],
                                 config: HttpArchiveConfig, seqNo: Int): Unit = {
    triedResponse match {
      case Success(response) =>
        if (response.status.isSuccess()) {
          val (futureStream, killSwitch) = processSource(
            createResponseDataSource(mid, response, config), mid, config, seqNo)
          processStreamResult(futureStream, killSwitch) { f =>
            ResponseProcessingError(mid, fileType, f.exception)
          }
        } else {
          sender() ! ResponseProcessingError(mid, fileType,
            new IllegalStateException(s"Failed response: ${response.status}"))
        }
      case Failure(exception) =>
        sender() ! ResponseProcessingError(mid, fileType, exception)
    }
  }
}
