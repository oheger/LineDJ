/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.config

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import de.oliver_heger.linedj.archivecommon.download.DownloadConfig
import org.apache.commons.configuration.Configuration

import scala.concurrent.duration._
import scala.util.Try

/**
  * A data class representing user credentials.
  *
  * This class holds credential information that is needed to connect to an
  * HTTP archive.
  *
  * @param userName the user name
  * @param password the password
  */
case class UserCredentials(userName: String, password: String)

object HttpArchiveConfig {
  /**
    * The prefix for all configuration properties related to the HTTP archive.
    */
  val PropPrefix = "media.http."

  /** The configuration property for the archive URI. */
  val PropArchiveUri: String = "archiveUri"

  /**
    * The configuration property for the name of the HTTP archive. The name is
    * optional, but recommended. If it is missing, a name is generated from the
    * archive URI.
    */
  val PropArchiveName: String = "archiveName"

  /** The configuration property for the processor count. */
  val PropProcessorCount: String = "processorCount"

  /** The configuration property for the processor timeout. */
  val PropProcessorTimeout: String = "processorTimeout"

  /** The configuration property for the maximum size of a content file. */
  val PropMaxContentSize: String = "maxContentSize"

  /**
    * The configuration property for the size of the in-memory buffer used to
    * buffer incoming data during a download operation. Note that this is a
    * mandatory property; no default value is provided.
    */
  val PropDownloadBufferSize: String = "downloadBufferSize"

  /**
    * The configuration property for the maximum inactivity interval during a
    * download operation. It is expected that an HTTP connection will be closed
    * if there is no activity for a given time frame. Therefore, a download
    * operation has to continue even if the client does not request further
    * data. This property defines the time span when a download actor has to
    * request another data chunk even if the client did not send a request.
    * Note that this is a mandatory property; no default value is provided.
    */
  val PropDownloadMaxInactivity: String = "downloadMaxInactivity"

  /**
    * The configuration property for the read chunk size during download
    * operations. This chunk size is applied when reading from a temporary
    * file that has been created during a download operation.
    */
  val PropDownloadReadChunkSize: String = "downloadReadChunkSize"

  /**
    * The configuration property for the chunk size of a read operation
    * triggered by the actor to prevent a timeout of the HTTP connection.
    */
  val PropTimeoutReadChunkSize: String = "timeoutReadChunkSize"

  /**
    * The default processor count value. This value is assumed if the
    * ''PropProcessorCount'' property is not specified.
    */
  val DefaultProcessorCount = 2

  /**
    * The default processor timeout value. This value is used if the
    * ''PropProcessorTimeout'' property is not specified
    */
  val DefaultProcessorTimeout = Timeout(1.minute)

  /**
    * The default maximum size for content files loaded from an HTTP archive
    * (in kilobytes). Responses with a larger size will be canceled.
    */
  val DefaultMaxContentSize = 64

  /**
    * The default chunk size for read operations from temporary files created
    * during download operations.
    */
  val DefaultDownloadReadChunkSize = 8192

  /** The separator for property keys. */
  private val Separator = "."

  /**
    * Tries to obtain a ''HttpArchiveConfig'' from the passed in
    * ''Configuration'' object. Properties are resolved in a relative way from
    * the given prefix key. If mandatory parameters are missing, the
    * operation fails. Otherwise, a ''Success'' object is returned wrapping
    * the extracted ''HttpArchiveConfig'' instance. Note that user credentials
    * have to be provided separately; it is typically not an option to store
    * credentials as plain text in a configuration file. The configuration for
    * download operations has to be provided separately as well; it may be
    * defined in a different configuration source.
    *
    * @param c              the ''Configuration''
    * @param prefix         the prefix path for all keys
    * @param credentials    user credentials
    * @param downloadConfig the download configuration
    * @return a ''Try'' with the extracted archive configuration
    */
  def apply(c: Configuration, prefix: String, credentials: UserCredentials,
            downloadConfig: DownloadConfig): Try[HttpArchiveConfig] = Try {
    val Path = if(prefix endsWith Separator) prefix else prefix + Separator
    val uri = c.getString(Path + PropArchiveUri)
    if (uri == null) {
      throw new IllegalArgumentException("No URI for HTTP archive configured!")
    }
    HttpArchiveConfig(c.getString(Path + PropArchiveUri),
      extractArchiveName(c, Path),
      credentials,
      c.getInt(Path + PropProcessorCount, DefaultProcessorCount),
      if (c.containsKey(Path + PropProcessorTimeout))
        Timeout(c.getInt(Path + PropProcessorTimeout), TimeUnit.SECONDS)
      else DefaultProcessorTimeout,
      c.getInt(Path + PropMaxContentSize, DefaultMaxContentSize),
      c getInt Path + PropDownloadBufferSize,
      c.getInt(Path + PropDownloadMaxInactivity).seconds,
      c.getInt(Path + PropDownloadReadChunkSize, DefaultDownloadReadChunkSize),
      c.getInt(Path + PropTimeoutReadChunkSize, DefaultDownloadReadChunkSize),
      downloadConfig)
  }

  /**
    * Extracts the name for the archive from the configuration. If not
    * specified, the name is extracted from the URI.
    *
    * @param c the configuration
    * @param prefix the prefix for configuration keys
    * @return the archive name
    */
  private def extractArchiveName(c: Configuration, prefix: String): String = {
    val name = c.getString(prefix + PropArchiveName)
    if (name != null) name
    else {
      val uri = Uri(c.getString(prefix + PropArchiveUri))
      val nameWithPath = uri.authority.host.address().replace('.', '_') +
        uri.path.toString().replace('/', '_')
      val posExt = nameWithPath lastIndexOf '.'
      if (posExt > 0) nameWithPath.substring(0, posExt) else nameWithPath
    }
  }
}

/**
  * A class defining the configuration settings to be applied for an HTTP
  * archive.
  *
  * @param archiveURI            the URI of the HTTP media archive
  * @param archiveName           a name for the HTTP media archive
  * @param credentials           credentials to connect to the archive
  * @param processorCount        the number of parallel processor actors to be used
  *                              when downloading meta data from the archive
  * @param processorTimeout      the timeout for calls to processor actors
  * @param maxContentSize        the maximum size of a content file (either a
  *                              settings or a meta data file) in kilobytes; if a
  *                              file is larger, it is canceled
  * @param downloadBufferSize    the size of the in-memory buffer for download
  *                              operations
  * @param downloadMaxInactivity definition of an inactivity span for download
  *                              operations; if no data is requested by the
  *                              client in this interval, a data chunk must be
  *                              requested from the HTTP archive
  * @param downloadReadChunkSize a chunk size when reading data from a temporary
  *                              file created during a download operation
  * @param timeoutReadChunkSize  a chunk size for requests sent to avoid a
  *                              timeout
  * @param downloadConfig        configuration for standard download properties
  */
case class HttpArchiveConfig(archiveURI: Uri,
                             archiveName: String,
                             credentials: UserCredentials,
                             processorCount: Int,
                             processorTimeout: Timeout,
                             maxContentSize: Int,
                             downloadBufferSize: Int,
                             downloadMaxInactivity: FiniteDuration,
                             downloadReadChunkSize: Int,
                             timeoutReadChunkSize: Int,
                             downloadConfig: DownloadConfig)
