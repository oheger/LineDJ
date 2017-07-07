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

package de.oliver_heger.linedj.archivehttp.impl.download

import java.nio.file.{Files, Path}

import akka.actor.{ActorLogging, ActorRef}
import akka.stream.KillSwitches
import akka.stream.scaladsl.{FileIO, Keep, Source}
import akka.util.ByteString
import de.oliver_heger.linedj.archivehttp.impl.download.WriteChunkActor.{StreamFailure,
WriteRequest, WriteResponse}
import de.oliver_heger.linedj.io.stream.{AbstractStreamProcessingActor, CancelableStreamSupport}

import scala.util.{Failure, Success, Try}

object WriteChunkActor {

  /**
    * A message to be processed by [[WriteChunkActor]] that triggers a write
    * operation. The path to the target file and the source with the data to
    * be written are specified. The sequence number can be used to identify the
    * request and bring it into an order.
    *
    * @param target the target path
    * @param source the source with data to be written
    * @param seqNo  a sequence number
    */
  case class WriteRequest(target: Path, source: Source[ByteString, Any], seqNo: Int)

  /**
    * A message sent by [[WriteChunkActor]] to notify a client about a
    * successful write operation.
    *
    * @param request reference to the original request
    */
  case class WriteResponse(request: WriteRequest)

  /**
    * Internal class representing a stream processing error.
    *
    * @param ex the exception
    */
  private case class StreamFailure(ex: Throwable)

}

/**
  * An actor class that writes chunks of a file to be downloaded to the local
  * hard disk.
  *
  * This functionality is required during download from an HTTP archive: If
  * the client does not request any data for a given time (e.g. because
  * playback is paused), the HTTP download may crash because of a timeout.
  * Therefore, data has to be downloaded with some rate even if there are no
  * requests from the client. To prevent unrestricted memory consumption, the
  * data has to be written to disk when a configurable size has been
  * downloaded. This is the task of this actor.
  *
  * The actor processes messages that specify the chunks of data and the target
  * file for the write operation. It sends a response message when the file has
  * been written successfully. In case of an error, it stops itself - then the
  * whole download should be aborted.
  */
class WriteChunkActor extends AbstractStreamProcessingActor with CancelableStreamSupport
  with ActorLogging {
  /**
    * The custom receive function. Here derived classes can provide their own
    * message handling.
    *
    * @return the custom receive method
    */
  override protected def customReceive: Receive = {
    case req: WriteRequest =>
      createTargetDirectoryIfNecessary(req) match {
        case Failure(e) =>
          log.error(e, "Could not create target directory for " + req.target)
          context stop self

        case Success(_) =>
          val sink = FileIO toPath req.target
          val (ks, futIO) = req.source.viaMat(KillSwitches.single)(Keep.right)
            .toMat(sink)(Keep.both)
            .run()
          val futWrite = futIO map { r =>
            r.status.get // throws in case of a failed operation
            WriteResponse(req)
          }
          processStreamResult(futWrite, ks)(f => StreamFailure(f.exception))
      }
  }

  /**
    * Sends the specified result to the original caller. This method is called
    * for each completed stream. This base implementation just sends the result
    * to the caller. Derived classes could override it to execute some
    * additional logic.
    *
    * @param client the client to receive the response
    * @param result the result message
    */
  override protected def propagateResult(client: ActorRef, result: Any): Unit = {
    result match {
      case StreamFailure(ex) =>
        log.error(ex, "Could not write file! Stopping actor.")
        context stop self

      case r =>
        super.propagateResult(client, r)
    }
  }

  /**
    * Creates the target directory. This method is called if the target
    * directory does not exist.
    *
    * @param dir the target directory
    * @return the newly created target path
    */
  private[download] def createTargetDirectory(dir: Path): Path = {
    Files.createDirectories(dir)
  }

  /**
    * Checks whether the target directory of the write operation already
    * exists. If not, it is attempted to be created now.
    *
    * @param req the write request
    * @return a ''Try'' with the target directory
    */
  private def createTargetDirectoryIfNecessary(req: WriteRequest): Try[Path] =
    Try {
      val dir = req.target.getParent
      if (!Files.exists(dir)) {
        createTargetDirectory(dir)
      } else dir
    }
}
