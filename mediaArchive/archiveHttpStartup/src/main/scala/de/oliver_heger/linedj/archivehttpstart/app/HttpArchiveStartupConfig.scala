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

package de.oliver_heger.linedj.archivehttpstart.app

import com.github.cloudfiles.core.http.UriEncodingHelper
import de.oliver_heger.linedj.archivecommon.download.DownloadConfig
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import org.apache.commons.configuration.Configuration
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.util.Timeout

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.util.Try

object HttpArchiveStartupConfig:
  /** The configuration property for the archive URI. */
  final val PropArchiveUri: String = "archiveUri"

  /**
    * The configuration property for the name of the HTTP archive. The name is
    * optional, but recommended. If it is missing, a name is generated from the
    * archive URI.
    */
  final val PropArchiveName: String = "archiveName"

  /**
    * The configuration property for the relative path to the archive's file
    * with the ToC. This file is loaded initially to find out, which media are
    * contained in this archive.
    */
  final val PropContentPath: String = "contentPath"

  /**
    * The configuration property for the relative path under which the media
    * files of this archive are stored.
    */
  final val PropMediaPath: String = "mediaPath"

  /**
    * The configuration property for the relative path under which the meta
    * data files of this archive are stored.
    */
  final val PropMetaDataPath: String = "metaDataPath"

  /** The configuration property for the processor count. */
  final val PropProcessorCount: String = "processorCount"

  /**
    * The configuration property for the processor timeout. This timeout is
    * also used when interacting with the archive, e.g. to download media data.
    */
  final val PropProcessorTimeout: String = "processorTimeout"

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
  final val PropPropagationBufSize: String = "propagationBufferSize"

  /** The configuration property for the maximum size of a content file. */
  final val PropMaxContentSize: String = "maxContentSize"

  /**
    * The configuration property for the size of the in-memory buffer used to
    * buffer incoming data during a download operation. Note that this is a
    * mandatory property; no default value is provided.
    */
  final val PropDownloadBufferSize: String = "downloadBufferSize"

  /**
    * The configuration property for the maximum inactivity interval during a
    * download operation. It is expected that an HTTP connection will be closed
    * if there is no activity for a given time frame. Therefore, a download
    * operation has to continue even if the client does not request further
    * data. This property defines the time span when a download actor has to
    * request another data chunk even if the client did not send a request.
    * Note that this is a mandatory property; no default value is provided.
    */
  final val PropDownloadMaxInactivity: String = "downloadMaxInactivity"

  /**
    * The configuration property for the read chunk size during download
    * operations. This chunk size is applied when reading from a temporary
    * file that has been created during a download operation.
    */
  final val PropDownloadReadChunkSize: String = "downloadReadChunkSize"

  /**
    * The configuration property for the size of data to read when an
    * inactivity timeout occurs. If there has been no download activity for a
    * while, the download actor automatically starts reading a block of data
    * with a configurable size to prevent that the connection gets closed from
    * the remote host. With this property the size of this block can be
    * specified. Note that this is a mandatory property; no default value is
    * provided.
    */
  final val PropTimeoutReadSize: String = "timeoutReadSize"

  /**
    * The prefix for all configuration properties related to the metadata
    * URI mapping configuration.
    */
  final val PrefixMetaUriMapping = "uriMapping."

  /**
    * The prefix for configuration properties related to the content URI
    * mapping configuration.
    */
  final val PrefixContentUriMapping = "contentUriMapping."

  /**
    * The configuration property for a prefix to be removed from a URI defined
    * in a metadata file. When doing the mapping it is expected that all URIs
    * start with this prefix - other URIs are ignored. The prefix is then
    * removed, so that the remaining part can be further processed, e.g.
    * concatenated to the root path of the current medium. If this property is
    * undefined, no prefix is removed.
    */
  final val PropMappingRemovePrefix = "removePrefix"

  /**
    * The configuration property for the number of path components to be
    * removed from the beginning of an URI. This value is evaluated after the
    * URI prefix has been removed. With this setting it is possible to remove
    * overlapping paths between the medium root path and the URIs pointing to
    * the files of this medium.
    */
  final val PropMappingRemoveComponents = "removePathComponents"

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
  final val PropMappingUriTemplate = "uriTemplate"

  /**
    * The configuration property that controls the URL encoding of URIs. If
    * set to '''true''', the single components of URIs obtained from metadata
    * files are encoded. (As they might contain path separators, those
    * separators are not encoded.) The default value is '''false'''.
    */
  final val PropMappingEncoding = "urlEncoding"

  /**
    * The configuration property defining the path separator used within URIs
    * of the HTTP archive. This is evaluated if the ''urlEncoding'' flag is
    * set. Then URIs are split at this separator, and the single components
    * are encoded. If no separator is provided, URL encoding is done on the
    * whole URI string.
    */
  final val PropMappingPathSeparator = "pathSeparator"

  /**
    * The configuration property defining the size of the request queue. This
    * is the maximum number of requests to the archive (download requests and
    * requests for metadata) waiting to be processed. If there are more
    * requests, new ones will be rejected.
    */
  final val PropRequestQueueSize = "requestQueueSize"

  /**
    * The configuration property that determines whether for this archive a
    * special cookie management needs to be installed. This is a boolean
    * property; if set to '''true''', the request actor used by the archive is
    * decorated in a special way.
    */
  final val PropNeedsCookieManagement = "needCookies"

  /**
    * The configuration property that determines whether the request actor
    * created for this archive should be equipped with logic for handling a
    * response with status 429 - Too Many Requests. If set to '''true''',
    * requests yielding such a response are retried.
    */
  final val PropNeedsRetrySupport = "needRetry"

  /**
    * The configuration property that defines the size of the cache for
    * encrypted URIs. When accessing a file system with encrypted names element
    * names need to be decrypted in order to resolve paths. To avoid repeating
    * decrypt operations, a cache can be configured that stores already
    * resolved paths.
    */
  final val PropCryptUriCacheSize = "cryptUriCacheSize"

  /**
    * The configuration property to set the chunk size when decrypting the
    * element names in a folder. A chunk of elements with this size is
    * decrypted, then the result is processed. Lower values leed to a higher
    * parallelism.
    */
  final val PropCryptNamesChunkSize = "cryptNamesChunkSize"

  /**
    * The default value for the content path. If not specified otherwise, the
    * archive's ToC is expected in a file named ''content.json'' in the root
    * folder.
    */
  final val DefaultContentPath = "content.json"

  /**
    * The default processor count value. This value is assumed if the
    * ''PropProcessorCount'' property is not specified.
    */
  final val DefaultProcessorCount = 2

  /**
    * The default processor timeout value. This value is used if the
    * ''PropProcessorTimeout'' property is not specified
    */
  final val DefaultProcessorTimeout: Timeout = Timeout(1.minute)

  /**
    * The default number of processed media that can be in the buffer for
    * propagation of content data.
    */
  final val DefaultPropagationBufSize = 4

  /**
    * The default maximum size for content files loaded from an HTTP archive
    * (in kilobytes). Responses with a larger size will be canceled.
    */
  final val DefaultMaxContentSize = 64

  /**
    * The default chunk size for read operations from temporary files created
    * during download operations.
    */
  final val DefaultDownloadReadChunkSize = 8192

  /** Default value for the request queue size property. */
  final val DefaultRequestQueueSize = 16

  /** Default value for the crypt URI cache size property. */
  final val DefaultCryptUriCacheSize = 1024

  /** Default value for the crypt chunk size property. */
  final val DefaultCryptNamesChunkSize = 32

  /** The separator for property keys. */
  private val Separator = "."

  /**
    * Tries to obtain a ''HttpArchiveStartupConfig'' from the passed in
    * ''Configuration'' object. Properties are resolved in a relative way from
    * the given prefix key. If mandatory parameters are missing, the
    * operation fails. Otherwise, a ''Success'' object is returned wrapping
    * the extracted ''HttpArchiveStartupConfig'' instance. Note that the
    * configuration for download operations has to be provided separately; it
    * may be defined in a different configuration source.
    *
    * In the resulting configuration, some fields are not yet defined;
    * especially the objects to actually access media files from the archive.
    * Their values can be generated by processing the other properties from the
    * startup configuration.
    *
    * @param c              the ''Configuration''
    * @param prefix         the prefix path for all keys
    * @param downloadConfig the download configuration
    * @return a ''Try'' with the extracted archive configuration
    */
  def apply(c: Configuration, prefix: String, downloadConfig: DownloadConfig): Try[HttpArchiveStartupConfig] = Try:
    val Path = if prefix endsWith Separator then prefix else prefix + Separator
    val uri = c.getString(Path + PropArchiveUri)
    if uri == null then
      throw new IllegalArgumentException("No URI for HTTP archive configured!")

    val archiveConfig = HttpArchiveConfig(processorCount = c.getInt(Path + PropProcessorCount, DefaultProcessorCount),
      processorTimeout = if c.containsKey(Path + PropProcessorTimeout) then
        Timeout(c.getInt(Path + PropProcessorTimeout), TimeUnit.SECONDS)
      else DefaultProcessorTimeout,
      propagationBufSize = c.getInt(Path + PropPropagationBufSize, DefaultPropagationBufSize),
      maxContentSize = c.getInt(Path + PropMaxContentSize, DefaultMaxContentSize),
      downloadBufferSize = c getInt Path + PropDownloadBufferSize,
      downloadMaxInactivity = c.getInt(Path + PropDownloadMaxInactivity).seconds,
      downloadReadChunkSize = c.getInt(Path + PropDownloadReadChunkSize, DefaultDownloadReadChunkSize),
      timeoutReadSize = c.getInt(Path + PropTimeoutReadSize),
      downloadConfig = downloadConfig,
      archiveBaseUri = Uri(uri),
      contentPath = extractContentPath(c, Path + PropContentPath),
      mediaPath = extractSubPath(c, Path + PropMediaPath),
      metadataPath = extractSubPath(c, Path + PropMetaDataPath),
      archiveName = extractArchiveName(c, Path),
      downloader = null)
    HttpArchiveStartupConfig(archiveConfig = archiveConfig,
      requestQueueSize = c.getInt(Path + PropRequestQueueSize, DefaultRequestQueueSize),
      cryptCacheSize = c.getInt(Path + PropCryptUriCacheSize, DefaultCryptUriCacheSize),
      cryptChunkSize = c.getInt(Path + PropCryptNamesChunkSize, DefaultCryptNamesChunkSize),
      needsCookieManagement = c.getBoolean(Path + PropNeedsCookieManagement, false),
      needsRetrySupport = c.getBoolean(Path + PropNeedsRetrySupport, false))

  /**
    * Extracts the name for the archive from the configuration. If not
    * specified, the name is extracted from the URI.
    *
    * @param c      the configuration
    * @param prefix the prefix for configuration keys
    * @return the archive name
    */
  private def extractArchiveName(c: Configuration, prefix: String): String =
    val name = c.getString(prefix + PropArchiveName)
    if name != null then name
    else
      val uri = Uri(c.getString(prefix + PropArchiveUri))
      uri.authority.host.address().replace('.', '_') +
        UriEncodingHelper.removeTrailingSeparator(uri.path.toString()).replace('/', '_')

  /**
    * Extracts a relative path to the archive's base URI from the
    * configuration. If this property is undefined, an empty path is returned.
    *
    * @param c   the configuration
    * @param key the key of the property in question
    * @return the resulting path
    */
  private def extractSubPath(c: Configuration, key: String): Uri.Path =
    if c.containsKey(key) then Uri.Path(UriEncodingHelper.removeLeadingSeparator(c.getString(key)))
    else Uri.Path.Empty

  /**
    * Extracts the path to the archive's content file.
    *
    * @param c   the configuration
    * @param key the key to the content path property
    * @return the resulting path
    */
  private def extractContentPath(c: Configuration, key: String): Uri.Path =
    Uri.Path(UriEncodingHelper.removeLeadingSeparator(c.getString(key, HttpArchiveStartupConfig.DefaultContentPath)))

/**
  * A data class defining configuration settings available for an HTTP archive.
  *
  * An instance of this class is created from the configuration of the HTTP
  * archive startup application for each archive listed in this configuration.
  * The data stored here can be divided into two parts:
  *  - One part required by the actors managing the archive; this is available
  *    in form of an [[HttpArchiveConfig]] object.
  *  - Another part required to setup the infrastructure to manage an archive.
  *    These are the other properties supported by this class.
  *
  * @param archiveConfig         the ''HttpArchiveConfig'' for managing this archive
  * @param requestQueueSize      the size of the request queue of the request
  *                              actor
  * @param cryptCacheSize        the size of the cache for decrypted paths
  * @param cryptChunkSize        the chunk size for decrypt operations
  * @param needsCookieManagement flag whether support for managing
  *                              proxy-related cookies is required
  * @param needsRetrySupport     flag whether retry logic for "429 Too Many
  *                              Requests" responses should be added
  */
case class HttpArchiveStartupConfig(archiveConfig: HttpArchiveConfig,
                                    requestQueueSize: Int,
                                    cryptCacheSize: Int,
                                    cryptChunkSize: Int,
                                    needsCookieManagement: Boolean,
                                    needsRetrySupport: Boolean)
