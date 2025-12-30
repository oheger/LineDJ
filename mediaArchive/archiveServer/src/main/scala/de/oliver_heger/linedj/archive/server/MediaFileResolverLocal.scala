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

package de.oliver_heger.linedj.archive.server

import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.server.MediaFileResolver.{FileResolverFunc, UnresolvableFileException}
import org.apache.pekko.stream.scaladsl.FileIO

import java.nio.file.Files
import scala.concurrent.Future

/**
  * An object providing functionality to resolve media files stored locally (on
  * the same machine). The functions defined here are based on the types 
  * defined by [[MediaFileResolver]]; they are specific to local media 
  * archives.
  */
object MediaFileResolverLocal:
  /**
    * Returns a [[FileResolverFunc]] that can resolve media files from archives
    * described by the given list of archive configurations. Based on the
    * passed in [[ArchiveModel.MediaFileDownloadInfo]], the function looks up
    * the owning archive and resolves the files URI against the archive's root
    * path.
    *
    * @param archiveConfigs a collection with configurations for local media
    *                       archives
    * @return the [[FileResolverFunc]] for files from these archives
    */
  def localFileResolverFunc(archiveConfigs: Iterable[MediaArchiveConfig]): FileResolverFunc =
    val archivePaths = archiveConfigs.map: archiveConfig =>
      archiveConfig.archiveName -> archiveConfig.rootPath
    .toMap

    (fileID, downloadInfo) =>
      archivePaths.get(downloadInfo.archiveName) match
        case Some(rootPath) =>
          val filePath = rootPath.resolve(downloadInfo.fileUri.path)
          if Files.isReadable(filePath) then
            Future.successful(FileIO.fromPath(filePath))
          else
            Future.failed(new UnresolvableFileException(fileID))
        case None =>
          Future.failed(
            new UnresolvableFileException(
              fileID,
              s"Could not resolve file '$fileID' in unknown archive '${downloadInfo.archiveName}'."
            )
          )
