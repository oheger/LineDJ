/*
 * Copyright 2015-2023 The Developers Team.
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

import akka.actor.Status
import akka.stream.KillSwitch
import akka.stream.scaladsl.Source
import akka.util.ByteString
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.io.stream.{AbstractStreamProcessingActor, CancelableStreamSupport, StreamSizeRestrictionStage}
import de.oliver_heger.linedj.shared.archive.media.MediumID

import scala.concurrent.Future

/**
  * An abstract base actor class for processing responses for meta data or
  * settings files received from an HTTP archive.
  *
  * A lot of functionality related to response handling is independent on the
  * concrete file type that has been requested. For instance, the data source
  * with the content of the response has to be prepared, support for
  * cancellation has to be implemented, processing results have to be sent to
  * the calling actor, etc. This base* class implements this common
  * functionality. Concrete subclasses mainly have to deal with setting up a
  * stream to process the content of the meta data file that has been
  * downloaded and to produce the results to be sent back to the caller.
  */
abstract class AbstractResponseProcessingActor
  extends AbstractStreamProcessingActor with CancelableStreamSupport {

  override def customReceive: Receive = {
    case ProcessResponse(mid, desc, data, config, seqNo) =>
      handleHttpResponse(mid, desc, data, config, seqNo)
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
    * @param desc   the medium description
    * @param config the archive configuration
    * @param seqNo  the sequence number of the current scan operation
    * @return a ''Future'' for the processing result and a ''KillSwitch''
    */
  protected def processSource(source: Source[ByteString, Any], mid: MediumID, desc: HttpMediumDesc,
                              config: HttpArchiveConfig, seqNo: Int): (Future[Any], KillSwitch)

  /**
    * Creates the source for the stream of the data to be processed. The source
    * provided by the [[ProcessResponse]] message is wrapped to enforce a size
    * restriction.
    *
    * @param source the original source
    * @param config the configuration of the HTTP archive
    * @return the source of the stream for the response's data bytes
    */
  private[impl] def createResponseDataSource(source: Source[ByteString, Any], config: HttpArchiveConfig):
  Source[ByteString, Any] =
    source.via(new StreamSizeRestrictionStage(config.maxContentSize * 1024))

  /**
    * Handles an HTTP response with the content of a meta data file. The
    * response is assumed to
    * be successful. (This is guaranteed by the processing stream.) Its entity
    * is parsed and converted.
    *
    * @param mid          the medium ID
    * @param responseData the source with the metadata to process
    * @param config       the HTTP archive configuration
    * @param seqNo        the current sequence number
    */
  private def handleHttpResponse(mid: MediumID, desc: HttpMediumDesc,
                                 responseData: Source[ByteString, Any], config: HttpArchiveConfig,
                                 seqNo: Int): Unit = {
    val (futureStream, killSwitch) = processSource(
      createResponseDataSource(responseData, config), mid, desc, config, seqNo)
    processStreamResult(futureStream, killSwitch) { f =>
      Status.Failure(f.exception)
    }
  }
}
