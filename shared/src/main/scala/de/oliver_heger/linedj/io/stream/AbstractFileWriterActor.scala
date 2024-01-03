/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.io.stream

import de.oliver_heger.linedj.io.stream.AbstractFileWriterActor.StreamFailure
import org.apache.pekko.actor.{ActorLogging, ActorRef}
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.scaladsl.{FileIO, Keep, Source}
import org.apache.pekko.util.ByteString

import java.nio.file.{Files, Path}
import scala.util.{Failure, Success, Try}

object AbstractFileWriterActor:

  /**
    * Class representing a stream processing error.
    *
    * @param ex   the exception
    * @param path the path that has been written
    */
  case class StreamFailure(ex: Throwable, path: Path)


/**
  * A base trait for actors that write data from a ''Source'' into a file on
  * disk.
  *
  * This trait provides a function that takes a source of type ''ByteString''
  * and a target path, creates a stream from these parameters, and writes the
  * single chunks of data into this file. If necessary, the parent directory is
  * created. (Because this actor does I/O operations, it makes sense to assign
  * it to a separate dispatcher.)
  *
  * By extending [[AbstractStreamProcessingActor]], the streams are managed
  * automatically and can be canceled.
  *
  * It is up to a concrete implementation to define the confirmation message
  * to be sent to the client after a successful write operation. In case of a
  * failed operation, the actor stops itself.
  */
trait AbstractFileWriterActor extends AbstractStreamProcessingActor:
  this: CancelableStreamSupport with ActorLogging =>
  /**
    * Sends the specified result to the original caller. This method is called
    * for each completed stream. This base implementation just sends the result
    * to the caller. Derived classes could override it to execute some
    * additional logic.
    *
    * @param client the client to receive the response
    * @param result the result message
    */
  override protected def propagateResult(client: ActorRef, result: Any): Unit =
    result match
      case f@StreamFailure(ex, p) =>
        log.error(ex, s"Could not write file $p!")
        handleFailure(client, f)

      case r =>
        super.propagateResult(client, r)

  /**
    * Executes a write operation by streaming the specified source into the
    * target file.
    *
    * @param source    the ''Source'' with the file data
    * @param target    the path to the target file
    * @param resultMsg the success message to be sent to the client
    * @param client    the client actor to receive a result message
    */
  protected def writeFile(source: Source[ByteString, Any], target: Path,
                          resultMsg: => Any, client: ActorRef = sender()): Unit =
    createTargetDirectoryIfNecessary(target) match
      case Failure(e) =>
        log.error(e, "Could not create target directory for " + target)
        handleFailure(client, StreamFailure(e, target))

      case Success(_) =>
        val sink = FileIO toPath target
        val (ks, futIO) = source.viaMat(KillSwitches.single)(Keep.right)
          .toMat(sink)(Keep.both)
          .run()
        val futWrite = futIO map { _ => resultMsg }
        processStreamResult(futWrite, ks, client)(f => StreamFailure(f.exception, target))

  /**
    * Method to handle a (fatal) error during a write operation. This
    * implementation stops the actor. Derived classes can define a different
    * error handling strategy.
    *
    * @param client the current client actor
    * @param failure an object with information about the failure
    */
  protected def handleFailure(client: ActorRef, failure: StreamFailure): Unit =
    context stop self

  /**
    * Creates the target directory. This method is called if the target
    * directory does not exist.
    *
    * @param dir the target directory
    * @return the newly created target path
    */
  private[stream] def createTargetDirectory(dir: Path): Path =
    Files.createDirectories(dir)

  /**
    * Checks whether the target directory of the write operation already
    * exists. If not, it is attempted to be created now.
    *
    * @param target the path to the target file
    * @return a ''Try'' with the target directory
    */
  private def createTargetDirectoryIfNecessary(target: Path): Try[Path] =
    Try:
      val dir = target.getParent
      if !Files.exists(dir) then
        createTargetDirectory(dir)
      else dir
