/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.io

import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.utils.Walk
import com.github.cloudfiles.localfs.{LocalFileSystem, LocalFsConfig}
import org.apache.pekko.actor.{ActorSystem, typed}
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.stream.scaladsl.Sink

import java.nio.file.Path
import scala.concurrent.Future

/**
  * A class providing some utility functions related to the local file system.
  */
object LocalFsUtils:
  /**
    * The default name of the blocking dispatcher. This dispatcher needs to be
    * declared in the application's configuration. It should be used when 
    * performing I/O operations; also, when working with a local filesystem.
    */
  final val DefaultBlockingDispatcherName = "blocking-dispatcher"

  /**
    * Constant for a string that is returned by ''extractExtension()'' if a
    * file does not have an extension. This is an empty string.
    */
  final val NoExtension = ""

  /** Constant for the extension delimiter character. */
  private val Dot = '.'

  /**
    * Extracts a file extension from the given path. If the file does not have
    * an extension, result is an empty string (as specified by the
    * ''NoExtension'' constant).
    *
    * @param path the path
    * @return the file extension
    */
  def extractExtension(path: String): String =
    val pos = path lastIndexOf Dot
    if pos >= 0 then path.substring(pos + 1)
    else NoExtension

  /**
    * Extracts the file extension from the given [[Path]]. This function is
    * analogous to the overloaded variant, but operates on ''Path'' objects.
    *
    * @param path the path
    * @return the file extension
    */
  def extractExtension(path: Path): String =
    extractExtension(path.getFileName.toString)

  /**
    * Creates an instance of [[LocalFileSystem]] for the given parameters.
    *
    * @param rootPath               the root path of the file system
    * @param blockingDispatcherName the name of the blocking dispatcher
    * @param system                 the actor system
    * @return the [[LocalFileSystem]] instance
    */
  def createLocalFs(rootPath: Path,
                    system: ActorSystem,
                    blockingDispatcherName: String = DefaultBlockingDispatcherName): LocalFileSystem =
    val ec = system.dispatchers.lookup(blockingDispatcherName)
    val fsOptions = LocalFsConfig(rootPath, ec)
    new LocalFileSystem(fsOptions)

  /**
    * Lists the files contained in a folder ignoring any subfolders. It is
    * possible to specify a set with file extensions to filter for; if an empty
    * set is passed, the result contains all files found in this folder.
    *
    * @param path                   the path to be listed
    * @param system                 the actor system
    * @param extensions             a set with extensions to filer for
    * @param blockingDispatcherName the name of the blocking dispatcher
    * @return a [[Future]] with the found files
    */
  def listFolder(path: Path,
                 system: ActorSystem,
                 extensions: Set[String] = Set.empty,
                 blockingDispatcherName: String = DefaultBlockingDispatcherName): Future[List[Path]] =
    def filterFiles(elements: List[Model.Element[Path]]): List[Model.Element[Path]] =
      elements.filter:
        case f: Model.File[Path] if extensions.isEmpty || extensions.contains(extractExtension(f.id)) => true
        case _ => false

    val localFs = LocalFsUtils.createLocalFs(path, system, blockingDispatcherName)
    val walkConfig = Walk.WalkConfig(
      fileSystem = localFs,
      httpActor = null,
      rootID = path,
      transform = filterFiles
    )

    given typed.ActorSystem[_] = system.toTyped

    val source = Walk.dfsSource(walkConfig)
      .map(_.id)
    val sink = Sink.fold[List[Path], Path](List.empty)((list, path) => path :: list)
    source.runWith(sink)
