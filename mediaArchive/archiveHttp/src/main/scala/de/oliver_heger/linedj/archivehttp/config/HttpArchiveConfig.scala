/*
 * Copyright 2015-2018 The Developers Team.
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
import de.oliver_heger.linedj.archivecommon.uri.UriMappingSpec
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

  /**
    * The configuration property for the number of processed media whose
    * content can be propagated to the union archive in parallel. Media in
    * the HTTP archive are processed one by one, and their content is
    * determined. This content then has to be propagated to the union archive.
    * As this may take longer than processing of the next medium, the data to
    * be sent to the union archive may pile up. This property defines the
    * number of media that can be buffered. If more processed media become
    * available, back-pressure is used to slow down stream processing.
    */
  val PropPropagationBufSize: String = "propagationBufferSize"

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
    * The configuration property for the size of data to read when an
    * inactivity timeout occurs. If there has been no download activity for a
    * while, the download actor automatically starts reading a block of data
    * with a configurable size to prevent that the connection gets closed from
    * the remote host. With this property the size of this block can be
    * specified. Note that this is a mandatory property; no default value is
    * provided.
    */
  val PropTimeoutReadSize: String = "timeoutReadSize"

  /**
    * The common prefix for all configuration properties related to the
    * URI-mapping configuration.
    */
  val PrefixUriMapping = "uriMapping."

  /**
    * The configuration property for a prefix to be removed from a URI defined
    * in a meta data file. When doing the mapping it is expected that all URIs
    * start with this prefix - other URIs are ignored. The prefix is then
    * removed, so that the remaining part can be further processed, e.g.
    * concatenated to the root path of the current medium. If this property is
    * undefined, no prefix is removed.
    */
  val PropMappingRemovePrefix: String = PrefixUriMapping + "removePrefix"

  /**
    * The configuration property for the number of path components to be
    * removed from the beginning of an URI. This value is evaluated after the
    * URI prefix has been removed. With this setting it is possible to remove
    * overlapping paths between the medium root path and the URIs pointing to
    * the files of this medium.
    */
  val PropMappingRemoveComponents: String = PrefixUriMapping + "removePathComponents"

  /**
    * The configuration property defining the template to be applied for URI
    * mapping. This template defines how resulting URIs look like. It is an
    * arbitrary string which can contain a few number of variables. The
    * variables are replaced by current values obtained during URI processing.
    * The following variables are supported:
    *
    *  - ''${medium}'' the root path to the medium the current file belongs to
    *  - ''${uri}'' the processed URI of the file
    *
    * For instance, the expression ''/test${medium}/${uri}'' generates relative
    * URIs (resolved against the root URI of the HTTP archive) pointing to
    * files below a path ''test'' that are structured in directories
    * corresponding to their media.
    *
    * The default value is ''${uri}'', i.e. the URI is used as is.
    */
  val PropMappingUriTemplate: String = PrefixUriMapping + "uriTemplate"

  /**
    * The configuration property that controls the URL encoding of URIs. If
    * set to '''true''', the single components of URIs obtained from meta data
    * files are encoded. (As they might contain path separators, those
    * separators are not encoded.) The default value is '''false'''.
    */
  val PropMappingEncoding: String = PrefixUriMapping + "urlEncoding"

  /**
    * The configuration property defining the path separator used within URIs
    * of the HTTP archive. This is evaluated if the ''urlEncoding'' flag is
    * set. Then URIs are split at this separator, and the single components
    * are encoded. If no separator is provided, URL encoding is done on the
    * whole URI string.
    */
  val PropMappingPathSeparator: String = PrefixUriMapping + "pathSeparator"

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
    * The default number of processed media that can be in the buffer for
    * propagation of content data.
    */
  val DefaultPropagationBufSize = 4

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

  /** Default value for the URI mapping template. */
  val DefaultUriMappingTemplate = "${uri}"

  /**
    * Default value for the number of path components to be removed. If not
    * explicitly specified, no path components will be removed.
    */
  val DefaultPathComponentsToRemove = 0

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
      c.getInt(Path + PropPropagationBufSize, DefaultPropagationBufSize),
      c.getInt(Path + PropMaxContentSize, DefaultMaxContentSize),
      c getInt Path + PropDownloadBufferSize,
      c.getInt(Path + PropDownloadMaxInactivity).seconds,
      c.getInt(Path + PropDownloadReadChunkSize, DefaultDownloadReadChunkSize),
      c.getInt(Path + PropTimeoutReadSize),
      downloadConfig,
      extractMappingConfig(c, Path))
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

  /**
    * Extracts the properties for URI mapping from the given configuration.
    *
    * @param c      the configuration
    * @param prefix the prefix for configuration keys
    * @return the URI mapping configuration
    */
  private def extractMappingConfig(c: Configuration, prefix: String): UriMappingConfig =
    UriMappingConfig(removePrefix = c.getString(prefix + PropMappingRemovePrefix),
      uriTemplate = c.getString(prefix + PropMappingUriTemplate, DefaultUriMappingTemplate),
      pathSeparator = c.getString(prefix + PropMappingPathSeparator),
      urlEncode = c.getBoolean(prefix + PropMappingEncoding, false),
      removeComponents = c.getInt(prefix + PropMappingRemoveComponents,
        DefaultPathComponentsToRemove))
}

/**
  * A configuration class to define the mapping of URIs read from meta data
  * files.
  *
  * The URIs stored in the meta data files read from an HTTP archive are not
  * necessarily in a form that they can be used directly for accessing files
  * from the archive. So a mapping may be required firs. This class combines
  * the properties that make up such a mapping.
  *
  * The properties are all optional; default values are used if they are not
  * specified.
  *
  * @param removePrefix a prefix to be removed from URIs
  * @param removeComponents the number of prefix path components to remove
  * @param uriTemplate  a template to construct the resulting URI
  * @param pathSeparator the path
  * @param urlEncode    flag whether the URI needs to be encoded
  */
case class UriMappingConfig(removePrefix: String, removeComponents: Int, uriTemplate: String,
                            pathSeparator: String, urlEncode: Boolean) extends UriMappingSpec {
  override val prefixToRemove: String = removePrefix

  override val pathComponentsToRemove: Int = removeComponents

  override val urlEncoding: Boolean = urlEncode

  override val uriPathSeparator: String = pathSeparator
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
  * @param propagationBufSize    number of processed media whose content can be
  *                              propagated to the union archive in parallel
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
  * @param timeoutReadSize       the size of data to read from the source to avoid
  *                              a timeout
  * @param downloadConfig        configuration for standard download properties
  * @param mappingConfig         configuration for URI mapping
  */
case class HttpArchiveConfig(archiveURI: Uri,
                             archiveName: String,
                             credentials: UserCredentials,
                             processorCount: Int,
                             processorTimeout: Timeout,
                             propagationBufSize: Int,
                             maxContentSize: Int,
                             downloadBufferSize: Int,
                             downloadMaxInactivity: FiniteDuration,
                             downloadReadChunkSize: Int,
                             timeoutReadSize: Int,
                             downloadConfig: DownloadConfig,
                             mappingConfig: UriMappingConfig)
