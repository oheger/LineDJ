/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.playlist.persistence

import de.oliver_heger.linedj.io.stream.AbstractFileWriterActor.StreamFailure
import de.oliver_heger.linedj.io.stream.{AbstractFileWriterActor, CancelableStreamSupport}
import de.oliver_heger.linedj.playlist.persistence.PlaylistFileWriterActor.{FileWritten, WriteFile}
import org.apache.pekko.actor.{ActorLogging, ActorRef}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import java.nio.file.Path

object PlaylistFileWriterActor:

  /**
    * A message processed by [[PlaylistFileWriterActor]] telling it to write
    * a file. The content of the file is determined by the given ''Source''.
    * The file is written to the given target path.
    *
    * @param source the source defining the file content
    * @param target the target path
    */
  case class WriteFile(source: Source[ByteString, Any], target: Path)

  /**
    * A message sent by [[PlaylistFileWriterActor]] in response of a file write
    * operation. The message indicates that the write operation is now
    * complete; by inspecting the ''Option'' with the exception, it can be
    * determined if it has been successful.
    *
    * @param target    the target path that has been written
    * @param exception an optional exception that caused the operation to fail
    */
  case class FileWritten(target: Path, exception: Option[Throwable])


/**
  * An actor class that handles write operations for files that store
  * information about a playlist.
  *
  * This actor is responsible for the actual IO operations. It slightly
  * extends its base class by processing of a corresponding write message, and
  * by a different error handling: If a write operation fails, the actor does
  * not stop itself, but sends a response message to the caller. It is
  * important that for each write request a response is returned.
  */
class PlaylistFileWriterActor extends AbstractFileWriterActor with CancelableStreamSupport with ActorLogging:
  override protected def customReceive: Receive =
    case WriteFile(source, target) =>
      writeFile(source, target, FileWritten(target, None))

  /**
    * @inheritdoc This implementation just sends a failure notification to the
    *             caller.
    */
  override protected def handleFailure(client: ActorRef, failure: StreamFailure): Unit =
    client ! FileWritten(failure.path, Some(failure.ex))
