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
import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import org.apache.pekko.stream.scaladsl.{FileIO, Source}
import org.apache.pekko.util.ByteString

import java.nio.file.Files
import scala.concurrent.{ExecutionContext, Future}

/**
  * An object providing functionality to resolve media files stored in a media
  * archive.
  *
  * The members of this object play an important role for downloading media
  * files. For a download operation, the archive must obtain a ''Source'' to
  * the content of the media file based on information about the file's
  * location. Depending on the archive type, this has to be done differently.
  * This object contains such resolver functions for different archive types.
  */
object MediaFileResolver:
  /**
    * A function type that abstracts over obtaining a (download) source for a
    * media file. The function is passed the ID of a media file and an object
    * with all relevant information about where the file is stored. Based on
    * this, it can perform the necessary steps (also asynchronously) to resolve
    * the file and return a [[Source]] with its content.
    */
  type FileResolverFunc = (String, ArchiveModel.MediaFileDownloadInfo) => Future[Source[ByteString, Any]]

  /**
    * A special exception class that can be used by concrete
    * [[FileResolverFunc]] implementations to indicate that a requested media
    * file cannot be resolved. This allows to distinguish a failure condition
    * because of a non-existing media file from an error that occurs during the
    * resolve operation.
    *
    * @param fileID  the ID of the affected file
    * @param message an optional message with further details
    */
  class UnresolvableFileException(val fileID: String, message: String = null)
    extends NoSuchElementException(
      if message == null then
        s"Cannot resolve media file '$fileID'."
      else
        message
    )

  /**
    * Transforms the passed in [[Future]] of a media file source to a
    * [[Future]] of an optional source that allows a better handling of
    * unresolvable files. If the future is successful, this function maps it to
    * a defined [[Option]]. Otherwise, the result depends on the stored
    * exception: if it is an [[UnresolvableFileException]], this is mapped to a
    * successful future with the value ''None''; other exceptions yield a
    * corresponding failed future. This allows a REST endpoint to send a
    * meaningful error code in case of a failure: 404 for a non-existing media
    * file, or 500 for other errors.
    *
    * @param futSource the [[Future]] with the source
    * @param ec        the execution context
    * @return the mapped [[Future]] of an optional source
    */
  def toOptionalSource(futSource: Future[Source[ByteString, Any]])
                      (using ec: ExecutionContext): Future[Option[Source[ByteString, Any]]] =
    futSource.map(src => Some(src))
      .recover:
        case _: UnresolvableFileException => None

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
