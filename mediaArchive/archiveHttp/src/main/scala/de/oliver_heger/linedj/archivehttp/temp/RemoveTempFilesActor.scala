/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.temp

import java.nio.file.Path

import akka.Done
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import de.oliver_heger.linedj.io.{DirectoryStreamSource, RemoveFileActor}
import de.oliver_heger.linedj.utils.ChildActorFactory

import scala.concurrent.Future

object RemoveTempFilesActor {

  /**
    * A message processed by [[RemoveTempFilesActor]] that causes the
    * specified files to be removed. All paths are passed to the managed
    * remove file actor. No response is sent.
    *
    * @param files the files to be removed
    */
  case class RemoveTempFiles(files: Iterable[Path])

  /**
    * A message processed by [[RemoveTempFilesActor]] that triggers a clean up
    * of the temporary directory for download files. All files and directories
    * created by older runs of the HTTP archive component are passed to the
    * remove file child actor.
    *
    * @param root      the root of the directory to be scanned
    * @param generator the generator for temp file names
    */
  case class ClearTempDirectory(root: Path, generator: TempPathGenerator)

  private class RemoteTempFilesActorImpl(dispatcher: String)
    extends RemoveTempFilesActor(dispatcher) with ChildActorFactory

  /**
    * Creates a ''Props'' object for the creation of a new actor instance.
    *
    * @param blockingDispatcherName the name of the blocking dispatcher
    * @return ''Props'' to create a new actor instance
    */
  def apply(blockingDispatcherName: String): Props =
    Props(classOf[RemoteTempFilesActorImpl], blockingDispatcherName)

  /**
    * An internally used message to trigger the 2nd phase of a clean temp
    * directory operation. In the first step all temporary files are removed.
    * In the second phase, the directories are processed. (Directories can only
    * be removed when they are empty; therefore, the files have to be handled
    * first.)
    *
    * @param root      the root directory to be scanned
    * @param generator the generator for temp file names
    */
  private case class ClearDirectoriesFromTempDirectory(root: Path, generator: TempPathGenerator)

  /**
    * A transformation function for the directory stream source. When
    * processing the temporary download directory in a first step all files
    * can be removed. Only then it is possible to remove directories. So,
    * depending on the step, either files or directories are mapped to
    * ''None'' values.
    *
    * @param dirFlag the directory flag to check for
    * @param p       the path to be mapped
    * @param isDir   a flag whether this path is a directory
    * @return the transformed stream element
    */
  private def transformByPathType(dirFlag: Boolean)(p: Path, isDir: Boolean): Option[Path] =
    if (isDir == dirFlag) Some(p)
    else None
}

/**
  * An actor class that allows removing of temporary download files.
  *
  * During a download operation, it may be necessary to create temporary files
  * for media content read from the server. After the download operation
  * terminates, these files have to be removed again.
  *
  * Removing temporary files is a fire-and-forget operation; if a file cannot
  * be removed for whatever reasons, no other parts of the HTTP archive should
  * be affected or crash. Therefore, no responses for remove operations are
  * expected. This actor class creates a child actor of type
  * [[de.oliver_heger.linedj.io.RemoveFileActor]] and delegates remove
  * operations to it. If an operation fails, the child actor crashes and is
  * then automatically restarted.
  *
  * To deal with remaining temporary files (which could not be removed in a
  * first attempt or whose responsible download actors crashed before they
  * could trigger a remove operation), there is functionality to scan the
  * root directory for temporary files and collect all temporary files
  * generated by older runs of the HTTP archive; they can then be deleted.
  * This functionality could be invoked for instance on startup of the
  * archive application.
  *
  * @param blockingDispatcherName the name of the dispatcher to be used for
  *                               the remove child actor
  */
class RemoveTempFilesActor(blockingDispatcherName: String) extends Actor with ActorLogging {
  this: ChildActorFactory =>

  import RemoveTempFilesActor._
  import context.{dispatcher, system}

  /** The actor which actually removes files. */
  private var removeFileActor: ActorRef = _

  override def preStart(): Unit = {
    removeFileActor = createChildActor(Props[RemoveFileActor]
      .withDispatcher(blockingDispatcherName))
  }

  override def receive: Receive = {
    case RemoveTempFiles(files) =>
      files map RemoveFileActor.RemoveFile foreach removeFileActor.!

    case RemoveFileActor.FileRemoved(path) =>
      log.debug("Removed temporary file {}.", path)

    case ClearTempDirectory(root, generator) =>
      processTempDirectory(root, generator, dirFlag = false) foreach { _ =>
        self ! ClearDirectoriesFromTempDirectory(root, generator)
      }

    case ClearDirectoriesFromTempDirectory(root, generator) =>
      processTempDirectory(root, generator, dirFlag = true)
  }

  /**
    * Scans the specified root directories for temporary download files and
    * removes all encountered ''Path'' objects of the specified path type.
    *
    * @param root      the root directory
    * @param generator the path generator
    * @param dirFlag   the path type: '''true''' for directories, '''false'''
    *                  for plain files
    * @return a future for the stream result
    */
  private def processTempDirectory(root: Path, generator: TempPathGenerator,
                                   dirFlag: Boolean): Future[Done] = {
    val filter = DirectoryStreamSource.PathFilter(generator.isRemovableTempPath)
    val source = DirectoryStreamSource.newDFSSource(root, filter)(transformByPathType(dirFlag))
    source.runForeach(_ foreach (removeFileActor ! RemoveFileActor.RemoveFile(_)))
  }
}
