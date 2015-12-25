/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.playlist.export

import java.nio.file.{Files, Path}

import akka.actor.Actor
import de.oliver_heger.linedj.pleditor.playlist.export.RemoveFileActor.{FileRemoved, RemoveFile}

object RemoveFileActor {

  /**
   * A message processed by [[RemoveFileActor]] triggering the removal of the
   * specified path. The path can point to a file or a directory. In the
   * latter case, it is assumed that the directory is empty and can be removed
   * directly. ([[RemoveFileActor]] only handles simple remove operations; no
   * recursive processing of directory structures is supported.)
   *
   * @param path the path to the file to be removed
   */
  case class RemoveFile(path: Path)

  /**
   * A message sent by [[RemoveFileActor]] after a file has been removed
   * successfully. After receiving this message the file should be gone.
   *
   * @param path the path to the file that has been removed
   */
  case class FileRemoved(path: Path)

}

/**
 * An actor for removing files.
 *
 * This actor is used to clean up the output structure before exporting the
 * media files of a playlist. It supports messages for removing a single file,
 * and reports back when this operation was successful.
 *
 * Errors when removing a file cause an exception to be thrown. This means that
 * the supervisor will be notified and can decide how to deal with this
 * problem.
 */
class RemoveFileActor extends Actor {
  override def receive: Receive = {
    case RemoveFile(path) =>
      Files delete path
      sender ! FileRemoved(path)
  }
}
