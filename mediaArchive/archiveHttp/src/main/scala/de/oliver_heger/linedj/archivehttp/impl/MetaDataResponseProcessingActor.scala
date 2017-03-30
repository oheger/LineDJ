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

import akka.actor.Actor
import akka.http.scaladsl.model.HttpResponse
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import de.oliver_heger.linedj.archivecommon.parser._
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.union.MetaDataProcessingResult

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object MetaDataResponseProcessingActor {
  /** Constant for the file type processed by this actor. */
  val FileType = "MetaData"
}

/**
  * An actor class responsible for processing a response for a meta data file.
  *
  * This actor parses the meta data file into a sequence of
  * [[MetaDataProcessingResult]] objects. This sequence is then passed in a
  * [[MetaDataResponseProcessingResult]] message to the sender actor.
  */
class MetaDataResponseProcessingActor extends Actor {

  import MetaDataResponseProcessingActor._

  /** The object for stream materialization. */
  private implicit val mat = ActorMaterializer()

  import context.dispatcher

  override def receive: Receive = {
    case ProcessResponse(mid, triedResponse, config) =>
      handleHttpResponse(mid, triedResponse, config)
  }

  /**
    * Processes the specified source with the content of the response entity
    * and returns a result. This result is then sent to the calling actor.
    *
    * @param source the source to be processed
    * @param mid    the current ''MediumID''
    * @return a ''Future'' for the processing result
    */
  protected def processSource(source: Source[ByteString, Any], mid: MediumID): Future[Any] = {
    source.via(new MetaDataParserStage(mid))
      .runFold(List.empty[MetaDataProcessingResult])((lst, r) => r :: lst)
      .map(MetaDataResponseProcessingResult(mid, _))
  }

  /**
    * Handle a HTTP response for a meta data file. Checks whether the response
    * is successful. If so, its entity is parsed and converted. Otherwise, a
    * failure message is sent to the sending actor.
    *
    * @param mid           the medium ID
    * @param triedResponse a ''Try'' for the HTTP response
    * @param config        the HTTP archive configuration
    */
  private def handleHttpResponse(mid: MediumID, triedResponse: Try[HttpResponse],
                                 config: HttpArchiveConfig): Unit = {
    triedResponse match {
      case Success(response) =>
        if (response.status.isSuccess()) {
          val client = sender()
          processSource(createResponseDataSource(mid, response, config), mid)
            .onComplete {
              case Success(result) =>
                client ! result
              case Failure(exception) =>
                client ! ResponseProcessingError(mid, FileType, exception)
            }
        } else {
          sender() ! ResponseProcessingError(mid, FileType,
            new IllegalStateException(s"Failed response: ${response.status}"))
        }
      case Failure(exception) =>
        sender() ! ResponseProcessingError(mid, FileType, exception)
    }
  }

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
      .via(new ResponseSizeRestrictionStage(config.maxContentSize * 1024))
}
