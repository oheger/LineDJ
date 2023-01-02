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

package de.oliver_heger.linedj.archive.protocol.onedrive

import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.cloudfiles.onedrive.{OneDriveConfig, OneDriveFileSystem, OneDriveModel}
import de.oliver_heger.linedj.archivehttp.io.HttpArchiveFileSystem
import de.oliver_heger.linedj.archivehttpstart.spi.HttpArchiveProtocolSpec

import scala.util.{Failure, Success, Try}

object OneDriveProtocolSpec {
  /** The name used for this protocol. */
  final val ProtocolName = "onedrive"
}

/**
  * Implementation of the OneDrive protocol to be used by HTTP archives.
  *
  * This class provides information required for accessing media files from a
  * OneDrive account. The actual access is done via a ''DavFileSystem'' from
  * the CloudFiles project.
  *
  * The OneDrive account to be accessed is configured using the archive URL,
  * which must be of the form ''driveID/path''. ''driveID'' is the account ID,
  * ''path'' is the root path in this account (without the document with the
  * archive's content).
  */
class OneDriveProtocolSpec
  extends HttpArchiveProtocolSpec[String, OneDriveModel.OneDriveFile, OneDriveModel.OneDriveFolder] {
  override val name: String = OneDriveProtocolSpec.ProtocolName

  override val requiresMultiHostSupport: Boolean = true

  override def createFileSystemFromConfig(sourceUri: String, timeout: Timeout):
  Try[HttpArchiveFileSystem[String, OneDriveModel.OneDriveFile, OneDriveModel.OneDriveFolder]] =
    if (UriEncodingHelper.hasLeadingSeparator(sourceUri))
      Failure(new IllegalArgumentException(s"Invalid archive URL '$sourceUri'. The URI must be of the form " +
        "<driveID>/<content root path>"))
    else {

      val archiveUri = UriEncodingHelper.removeTrailingSeparator(sourceUri)
      val posPath = archiveUri.indexOf(UriEncodingHelper.UriSeparator)
      val (driveID, rootPath) = if (posPath <= 0)
        (archiveUri, Uri.Path.Empty)
      else
        (archiveUri.substring(0, posPath), Uri.Path(archiveUri.substring(posPath)))

      val optRootPath = if (rootPath.isEmpty) None else Some(rootPath.toString())
      val config = OneDriveConfig(driveID = driveID, optRootPath = optRootPath, timeout = timeout)
      val fileSystem = new OneDriveFileSystem(config)
      Success(HttpArchiveFileSystem(fileSystem, rootPath))
    }
}
