/*
 * Copyright 2015-2019 The Developers Team.
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

import java.nio.file.Path
import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import de.oliver_heger.linedj.archivecommon.download.DownloadConfig
import de.oliver_heger.linedj.archivecommon.uri.UriMappingSpec
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig.AuthConfigureFunc
import de.oliver_heger.linedj.archivehttp.crypt.Secret
import de.oliver_heger.linedj.archivehttp.impl.uri.UriUtils
import de.oliver_heger.linedj.archivehttp.spi.HttpArchiveProtocol
import de.oliver_heger.linedj.shared.archive.media.UriHelper
import de.oliver_heger.linedj.utils.ChildActorFactory
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
  * @param password the password as a ''Secret''
  */
case class UserCredentials(userName: String, password: Secret)

/**
  * A data class with information required to access the persistent information
  * related to an OAuth identity provider.
  *
  * When loading or storing an OAuth configuration an instance of this class
  * must be provided. It specifies the storage location of the configuration
  * and also determines some other properties like the encryption status.
  *
  * The whole OAuth configuration for a specific identity provider (IDP) is
  * stored in multiple files in a directory. All files belonging to the same
  * configuration share the same base name. Sensitive information like tokens
  * or client secrets are encrypted using the password provided.
  *
  * @param rootDir     the root directory where all files are stored
  * @param baseName    the base name of the files of this configuration
  * @param encPassword the password to encrypt sensitive files
  */
case class OAuthStorageConfig(rootDir: Path,
                              baseName: String,
                              encPassword: Secret) {
  /**
    * Returns a path in the root directory that is derived from the base name
    * with the given suffix. This is useful to locate concrete files related to
    * a specific IDP configuration.
    *
    * @param suffix the suffix to append to the base name
    * @return the path pointing to the file with the given suffix
    */
  def resolveFileName(suffix: String): Path =
    rootDir.resolve(baseName + suffix)
}

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
    * The prefix for all configuration properties related to the meta data
    * URI mapping configuration.
    */
  val PrefixMetaUriMapping = "uriMapping."

  /**
    * The prefix for configuration properties related to the content URI
    * mapping configuration.
    */
  val PrefixContentUriMapping = "contentUriMapping."

  /**
    * The configuration property for a prefix to be removed from a URI defined
    * in a meta data file. When doing the mapping it is expected that all URIs
    * start with this prefix - other URIs are ignored. The prefix is then
    * removed, so that the remaining part can be further processed, e.g.
    * concatenated to the root path of the current medium. If this property is
    * undefined, no prefix is removed.
    */
  val PropMappingRemovePrefix = "removePrefix"

  /**
    * The configuration property for the number of path components to be
    * removed from the beginning of an URI. This value is evaluated after the
    * URI prefix has been removed. With this setting it is possible to remove
    * overlapping paths between the medium root path and the URIs pointing to
    * the files of this medium.
    */
  val PropMappingRemoveComponents = "removePathComponents"

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
  val PropMappingUriTemplate = "uriTemplate"

  /**
    * The configuration property that controls the URL encoding of URIs. If
    * set to '''true''', the single components of URIs obtained from meta data
    * files are encoded. (As they might contain path separators, those
    * separators are not encoded.) The default value is '''false'''.
    */
  val PropMappingEncoding = "urlEncoding"

  /**
    * The configuration property defining the path separator used within URIs
    * of the HTTP archive. This is evaluated if the ''urlEncoding'' flag is
    * set. Then URIs are split at this separator, and the single components
    * are encoded. If no separator is provided, URL encoding is done on the
    * whole URI string.
    */
  val PropMappingPathSeparator = "pathSeparator"

  /**
    * The configuration property defining the size of the request queue. This
    * is the maximum number of requests to the archive (download requests and
    * requests for meta data) waiting to be processed. If there are more
    * requests, new ones will be rejected.
    */
  val PropRequestQueueSize = "requestQueueSize"

  /**
    * The configuration property defining the size of the cache for encrypted
    * URIs. This property is evaluated for encrypted archives. In this case,
    * URIs reference plain directory and file names and have to be resolved
    * first before the execution of a request. Because URI resolving can  be
    * expensive a cache is maintained which stores already resolved URIs. With
    * this property the size of this cache can be determined. As this cache
    * only stores strings representing URIs, it does not consume that much
    * memory; so it should be safe to set a larger number.
    */
  val PropCryptUriCacheSize = "cryptUriCacheSize"

  /**
    * The configuration property that determines whether for this archive a
    * special cookie management needs to be installed. This is a boolean
    * property; if set to '''true''', the request actor used by the archive is
    * decorated in a special way.
    */
  val PropNeedsCookieManagement = "needCookies"

  /**
    * The default processor count value. This value is assumed if the
    * ''PropProcessorCount'' property is not specified.
    */
  val DefaultProcessorCount = 2

  /**
    * The default processor timeout value. This value is used if the
    * ''PropProcessorTimeout'' property is not specified
    */
  val DefaultProcessorTimeout: Timeout = Timeout(1.minute)

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

  /** Default value for the request queue size property. */
  val DefaultRequestQueueSize = 16

  /** Default value for the crypt URI cache size property. */
  val DefaultCryptUriCacheSize = 1024

  /** The separator for property keys. */
  private val Separator = "."

  /**
    * Definition of a function that can configure the authentication to be used
    * by an HTTP archive. The basic idea is that the plain request actor
    * created for the archive is decorated to handle the required
    * authentication scheme transparently. Therefore, the function is passed
    * the original request actor and a factory to create additional extension
    * actors as needed.
    */
  type AuthConfigureFunc = (ActorRef, ChildActorFactory) => ActorRef

  /**
    * Tries to obtain a ''HttpArchiveConfig'' from the passed in
    * ''Configuration'' object. Properties are resolved in a relative way from
    * the given prefix key. If mandatory parameters are missing, the
    * operation fails. Otherwise, a ''Success'' object is returned wrapping
    * the extracted ''HttpArchiveConfig'' instance. Note that the
    * authentication mechanism has to be provided separately; it is typically
    * not an option to store credentials as plain text in a configuration file.
    * The configuration for download operations has to be provided separately
    * as well; it may be defined in a different configuration source. Finally,
    * the HTTP-based protocol used by the archive is required; this is an
    * active object which must be created by the caller.
    *
    * @param c              the ''Configuration''
    * @param prefix         the prefix path for all keys
    * @param downloadConfig the download configuration
    * @param protocol       the protocol to access the archive
    * @param authFunc       the function to setup authentication
    * @return a ''Try'' with the extracted archive configuration
    */
  def apply(c: Configuration, prefix: String, downloadConfig: DownloadConfig,
            protocol: HttpArchiveProtocol, authFunc: AuthConfigureFunc): Try[HttpArchiveConfig] = Try {
    val Path = if (prefix endsWith Separator) prefix else prefix + Separator
    val uri = c.getString(Path + PropArchiveUri)
    if (uri == null) {
      throw new IllegalArgumentException("No URI for HTTP archive configured!")
    }
    HttpArchiveConfig(c.getString(Path + PropArchiveUri),
      extractArchiveName(c, Path),
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
      extractMappingConfig(c, Path + PrefixMetaUriMapping),
      extractMappingConfig(c, Path + PrefixContentUriMapping),
      c.getInt(Path + PropRequestQueueSize, DefaultRequestQueueSize),
      c.getInt(Path + PropCryptUriCacheSize, DefaultCryptUriCacheSize),
      c.getBoolean(Path + PropNeedsCookieManagement, false),
      protocol,
      authFunc)
  }

  /**
    * Extracts the properties for URI mapping from the given configuration.
    *
    * @param c      the configuration
    * @param prefix the prefix for configuration keys (must end with a
    *               separator)
    * @return the URI mapping configuration
    */
  def extractMappingConfig(c: Configuration, prefix: String): UriMappingConfig =
    UriMappingConfig(removePrefix = c.getString(prefix + PropMappingRemovePrefix),
      uriTemplate = c.getString(prefix + PropMappingUriTemplate, DefaultUriMappingTemplate),
      pathSeparator = c.getString(prefix + PropMappingPathSeparator),
      urlEncode = c.getBoolean(prefix + PropMappingEncoding, false),
      removeComponents = c.getInt(prefix + PropMappingRemoveComponents,
        DefaultPathComponentsToRemove))

  /**
    * Extracts the name for the archive from the configuration. If not
    * specified, the name is extracted from the URI.
    *
    * @param c      the configuration
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
  * @param removePrefix     a prefix to be removed from URIs
  * @param removeComponents the number of prefix path components to remove
  * @param uriTemplate      a template to construct the resulting URI
  * @param pathSeparator    the path
  * @param urlEncode        flag whether the URI needs to be encoded
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
  * @param metaMappingConfig     configuration for URI mapping for meta data
  * @param contentMappingConfig  configuration for URI mapping for the archive
  *                              content file
  * @param requestQueueSize      the size of the queue for pending requests
  * @param cryptUriCacheSize     the size of the cache for decrypted URIs
  * @param needsCookieManagement flag whether the archive requires cookies to
  *                              be set; this causes a special decoration of
  *                              the request actor
  * @param protocol              the HTTP-based protocol for the archive
  * @param authFunc              the func to setup authentication
  */
case class HttpArchiveConfig(archiveURI: Uri,
                             archiveName: String,
                             processorCount: Int,
                             processorTimeout: Timeout,
                             propagationBufSize: Int,
                             maxContentSize: Int,
                             downloadBufferSize: Int,
                             downloadMaxInactivity: FiniteDuration,
                             downloadReadChunkSize: Int,
                             timeoutReadSize: Int,
                             downloadConfig: DownloadConfig,
                             metaMappingConfig: UriMappingConfig,
                             contentMappingConfig: UriMappingConfig,
                             requestQueueSize: Int,
                             cryptUriCacheSize: Int,
                             needsCookieManagement: Boolean,
                             protocol: HttpArchiveProtocol,
                             authFunc: AuthConfigureFunc) {
  /**
    * A sequence with the single components of the archive URI. This is needed
    * when constructing relative URIs for songs contained in the archive.
    */
  val archiveUriComponents: Seq[String] = UriUtils.uriComponents(archiveURI.toString())

  /**
    * The base URI of the represented archive. All relative paths to media
    * files need to be resolved against this URI.
    */
  val archiveBaseUri: String = UriHelper.extractParent(archiveURI.toString())

  /**
    * Resolves the given (relative) path against the base URI of the
    * represented archive. The path MUST start with a slash.
    *
    * @param path the relative path to be resolved
    * @return the resulting absolute URI
    */
  def resolvePath(path: String): Uri = archiveBaseUri + path
}
