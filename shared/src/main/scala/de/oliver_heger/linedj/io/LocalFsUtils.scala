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

package de.oliver_heger.linedj.io

import com.github.cloudfiles.localfs.{LocalFileSystem, LocalFsConfig}
import org.apache.pekko.actor.ActorSystem

import java.nio.file.Path

/**
  * A class providing some utility functions related to the local file system.
  */
object LocalFsUtils:
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
  def createLocalFs(rootPath: Path, blockingDispatcherName: String, system: ActorSystem): LocalFileSystem =
    val ec = system.dispatchers.lookup(blockingDispatcherName)
    val fsOptions = LocalFsConfig(rootPath, ec)
    new LocalFileSystem(fsOptions)
