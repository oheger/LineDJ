/*
 * Copyright 2015-2026 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.archive.server.cloud

import de.oliver_heger.linedj.archive.cloud.{CloudArchiveConfig, CloudFileDownloader}
import de.oliver_heger.linedj.archive.server.cloud.ContentDownloader.metadataFileName
import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import scala.concurrent.Future

object ContentDownloader:
  /** The extension of metadata documents in cloud archives. */
  final val MetadataExtension = ".mdt"

  /**
    * Returns the name of the document with metadata for the given medium ID.
    *
    * @param mediumID the medium ID
    * @return the name of the metadata document for this medium
    */
  def metadataFileName(mediumID: Checksums.MediumChecksum): String =
    s"${mediumID.checksum}$MetadataExtension"
end ContentDownloader

/**
  * A helper class that provides some use case-specific functionality on top of
  * a [[CloudFileDownloader]]. The class offers functionality to download
  * specific documents from a cloud archive. It has access to a configuration
  * that defines the corresponding locations of these documents.
  *
  * @param archiveConfig  the configuration for the affected archive
  * @param fileDownloader the downloader to access this archive
  */
class ContentDownloader(val archiveConfig: CloudArchiveConfig,
                        val fileDownloader: CloudFileDownloader):
  /**
    * Returns a source to download the content document of the associated cloud
    * archive.
    *
    * @return a [[Future]] with the [[Source]] of the content document
    */
  def loadContentDocument(): Future[Source[ByteString, Any]] =
    fileDownloader.downloadFile(archiveConfig.contentPath)

  /**
    * Returns a source to download the description of a medium at the given
    * path from the associated cloud archive.
    *
    * @param descriptionPath the relative description path of the medium
    * @return a [[Future]] with the [[Source]] of this description document
    */
  def loadMediumDescription(descriptionPath: String): Future[Source[ByteString, Any]] =
    fileDownloader.downloadFile(archiveConfig.mediaPath, descriptionPath)

  /**
    * Returns a source to download the metadata document of a specific medium
    * from the associated cloud archive.
    *
    * @param mediumID the ID of the affected medium
    * @return a [[Future]] with the [[Source]] of the metadata document
    */
  def loadMediumMetadata(mediumID: Checksums.MediumChecksum): Future[Source[ByteString, Any]] =
    fileDownloader.downloadFile(archiveConfig.metadataPath, metadataFileName(mediumID))
