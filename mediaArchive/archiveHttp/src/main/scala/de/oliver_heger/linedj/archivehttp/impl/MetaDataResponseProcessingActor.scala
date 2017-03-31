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

import akka.actor.{Actor, ActorRef}
import akka.http.scaladsl.model.HttpResponse
import akka.stream.{ActorMaterializer, KillSwitch, KillSwitches}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.ByteString
import de.oliver_heger.linedj.archivecommon.parser._
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.union.MetaDataProcessingResult

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object MetaDataResponseProcessingActor {
  /** Constant for the file type processed by this actor. */
  val FileType = "MetaData"

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
  * An actor class responsible for processing a response for a meta data file.
  *
  * This actor parses the meta data file into a sequence of
  * [[MetaDataProcessingResult]] objects. This sequence is then passed in a
  * [[MetaDataResponseProcessingResult]] message to the sender actor.
  */
class MetaDataResponseProcessingActor extends Actor with CancelableStreamSupport {

  import MetaDataResponseProcessingActor._

  /** The object for stream materialization. */
  private implicit val mat = ActorMaterializer()

  import context.dispatcher

  override def receive: Receive = {
    case ProcessResponse(mid, triedResponse, config) =>
      handleHttpResponse(mid, triedResponse, config)

    case CancelProcessing =>
      cancelCurrentStreams()

    case StreamCompleted(client, result, killSwitchID) =>
      client ! result
      unregisterKillSwitch(killSwitchID)
  }

  /**
    * Processes the specified source with the content of the response entity
    * and returns a result and an object to cancel processing on an external
    * request. When the ''Future'' completes its result is sent to the calling
    * actor. The ''KillSwitch'' is stored temporarily, so that the stream can
    * be canceled if necessary.
    *
    * @param source the source to be processed
    * @param mid    the current ''MediumID''
    * @return a ''Future'' for the processing result and a ''KillSwitch''
    */
  protected def processSource(source: Source[ByteString, Any], mid: MediumID):
  (Future[Any], KillSwitch) = {
    val sink = Sink.fold[List[MetaDataProcessingResult],
      MetaDataProcessingResult](List.empty)((lst, r) => r :: lst)
    val (killSwitch, futStream) = source.via(new MetaDataParserStage(mid))
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(sink)(Keep.both)
      .run
    (futStream.map(MetaDataResponseProcessingResult(mid, _)), killSwitch)
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
          val (futureStream, killSwitch) = processSource(
            createResponseDataSource(mid, response, config), mid)
          val killSwitchID = registerKillSwitch(killSwitch)
          futureStream.onComplete { triedResult =>
            handleStreamCompletion(mid, client, killSwitchID, triedResult)
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
    * Handles the result when a stream completes. This method produces either
    * a success or an error result. A special message is sent to ''self'',
    * so that the result can be evaluated by the actor and cleanup for the
    * completed stream can be done.
    *
    * @param mid          the medium ID
    * @param client       the client actor
    * @param killSwitchID the ID of the kill switch of the stream
    * @param triedResult  the result of stream processing
    */
  private def handleStreamCompletion(mid: MediumID, client: ActorRef, killSwitchID: Int,
                                     triedResult: Try[Any]): Unit = {
    val procResult = triedResult match {
      case Success(result) =>
        result
      case Failure(exception) =>
        ResponseProcessingError(mid, FileType, exception)
    }
    self ! StreamCompleted(client, procResult, killSwitchID)
  }
}
