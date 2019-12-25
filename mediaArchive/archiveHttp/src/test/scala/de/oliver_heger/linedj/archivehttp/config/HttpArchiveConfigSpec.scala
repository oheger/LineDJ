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

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import de.oliver_heger.linedj.archivecommon.download.DownloadConfig
import de.oliver_heger.linedj.archivecommon.uri.UriMapper
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig.AuthConfigureFunc
import de.oliver_heger.linedj.archivehttp.spi.HttpArchiveProtocol
import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object HttpArchiveConfigSpec {
  /** The URI to the HTTP archive. */
  private val ArchiveUri = "https://music.archive.org/music/test/content.json"

  /** A test download configuration. */
  private val DownloadData = DownloadConfig(1.hour, 10.minutes, 8192)

  /** The default prefix for property keys. */
  private val Prefix = "media.http"

  /** The name of the test archive. */
  private val ArchiveName = "CoolMusicArchive"

  /** The number of processors. */
  private val ProcessorCount = 8

  /** The timeout for processor actors. */
  private val ProcessorTimeout = 60

  /** The size of the propagation buffer. */
  private val PropagationBufSize = 8

  /** The maximum content size value. */
  private val MaxContentSize = 10

  /** The download buffer size value. */
  private val DownloadBufferSize = 16384

  /** The inactivity timeout for download operations. */
  private val DownloadMaxInactivity = 10.minutes

  /** The read chunk size for download operations. */
  private val DownloadReadChunkSize = 8000

  /** The amount of data to read when a timeout occurs. */
  private val TimeoutReadSize = 256 * 1024

  /** The prefix to be removed during URI mapping. */
  private val RemovePrefix = "path://"

  /** The number of path components to be removed for an URI. */
  private val RemoveComponentCount = 4

  /** The template for URIs. */
  private val UriTemplate = "/foo/${uri}"

  /** The path separator for URIs. */
  private val UriPathSeparator = "\\"

  /** The size of the request queue. */
  private val RequestQueueSize = 64

  /** The size of the cache for encrypted URIs. */
  private val CryptCacheSize = 2222

  /** A test authentication function. */
  private val TestAuthFunc: AuthConfigureFunc = (actor, _) => actor

  /**
    * Creates a configuration object with all test settings.
    *
    * @param at the path to the properties
    * @return the test configuration
    */
  private def createConfiguration(at: String = Prefix): Configuration = {
    val c = new PropertiesConfiguration
    c.addProperty(at + ".archiveUri", ArchiveUri)
    c.addProperty(at + ".archiveName", ArchiveName)
    c.addProperty(at + ".processorCount", ProcessorCount)
    c.addProperty(at + ".processorTimeout", ProcessorTimeout)
    c.addProperty(at + ".propagationBufferSize", PropagationBufSize)
    c.addProperty(at + ".maxContentSize", MaxContentSize)
    c.addProperty(at + ".downloadBufferSize", DownloadBufferSize)
    c.addProperty(at + ".downloadMaxInactivity", DownloadMaxInactivity.toSeconds)
    c.addProperty(at + ".downloadReadChunkSize", DownloadReadChunkSize)
    c.addProperty(at + ".timeoutReadSize", TimeoutReadSize)
    c.addProperty(at + ".uriMapping.removePrefix", RemovePrefix)
    c.addProperty(at + ".uriMapping.removePathComponents", RemoveComponentCount)
    c.addProperty(at + ".uriMapping.uriTemplate", UriTemplate)
    c.addProperty(at + ".uriMapping.pathSeparator", UriPathSeparator)
    c.addProperty(at + ".uriMapping.urlEncoding", true)
    c.addProperty(at + ".requestQueueSize", RequestQueueSize)
    c.addProperty(at + ".cryptUriCacheSize", CryptCacheSize)
    c.addProperty(at + ".needCookies", true)
    c
  }

  /**
    * Clears the value of the specified property from the configuration.
    *
    * @param c   the configuration
    * @param key the (relative) property key
    * @return the configuration
    */
  private def clearProperty(c: Configuration, key: String): Configuration = {
    c.clearProperty(Prefix + "." + key)
    c
  }
}

/**
  * Test class for ''HttpArchiveConfig''.
  */
class HttpArchiveConfigSpec extends FlatSpec with Matchers with MockitoSugar {

  import HttpArchiveConfigSpec._

  /**
    * Checks whether the specified configuration contains the expected
    * default properties.
    *
    * @param triedConfig a ''Try'' for the config to check
    * @return the configuration
    */
  private def checkConfig(triedConfig: Try[HttpArchiveConfig]): HttpArchiveConfig = {
    triedConfig match {
      case Success(config) =>
        config.archiveURI should be(Uri(ArchiveUri))
        config.archiveName should be(ArchiveName)
        config.processorCount should be(ProcessorCount)
        config.processorTimeout should be(Timeout(ProcessorTimeout, TimeUnit.SECONDS))
        config.propagationBufSize should be(PropagationBufSize)
        config.maxContentSize should be(MaxContentSize)
        config.downloadBufferSize should be(DownloadBufferSize)
        config.downloadReadChunkSize should be(DownloadReadChunkSize)
        config.downloadMaxInactivity should be(DownloadMaxInactivity)
        config.timeoutReadSize should be(TimeoutReadSize)
        config.metaMappingConfig.removePrefix should be(RemovePrefix)
        config.metaMappingConfig.pathComponentsToRemove should be(RemoveComponentCount)
        config.metaMappingConfig.uriTemplate should be(UriTemplate)
        config.metaMappingConfig.pathSeparator should be(UriPathSeparator)
        config.metaMappingConfig.urlEncode shouldBe true
        config.contentMappingConfig.removePrefix should be(null)
        config.contentMappingConfig.pathComponentsToRemove should be(0)
        config.contentMappingConfig.uriTemplate should be(HttpArchiveConfig
          .DefaultUriMappingTemplate)
        config.contentMappingConfig.urlEncode shouldBe false
        config.requestQueueSize should be(RequestQueueSize)
        config.cryptUriCacheSize should be(CryptCacheSize)
        config.needsCookieManagement shouldBe true
        config
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  /**
    * Creates a test archive configuration with default properties.
    *
    * @param c the underlying configuration object
    * @return the test archive configuration
    */
  private def createArchiveConfig(c: Configuration): Try[HttpArchiveConfig] =
    HttpArchiveConfig(c, Prefix, DownloadData, mock[HttpArchiveProtocol], TestAuthFunc)

  "An HttpArchiveConfig" should "process a valid configuration" in {
    val c = createConfiguration()
    val protocol = mock[HttpArchiveProtocol]

    val config = checkConfig(HttpArchiveConfig(c, Prefix, DownloadData, protocol, TestAuthFunc))
    config.authFunc should be(TestAuthFunc)
    config.protocol should be(protocol)
  }

  it should "initialize a content mapping configuration" in {
    val c = createConfiguration()
    val pref = Prefix + ".contentUriMapping."
    c.addProperty(pref + "removePrefix", RemovePrefix)
    c.addProperty(pref + "removePathComponents", RemoveComponentCount)
    c.addProperty(pref + "uriTemplate", UriTemplate)
    c.addProperty(pref + "pathSeparator", UriPathSeparator)
    c.addProperty(pref + "urlEncoding", true)

    createArchiveConfig(c) match {
      case Success(config) =>
        config.contentMappingConfig.removePrefix should be(RemovePrefix)
        config.contentMappingConfig.pathComponentsToRemove should be(RemoveComponentCount)
        config.contentMappingConfig.uriTemplate should be(UriTemplate)
        config.contentMappingConfig.pathSeparator should be(UriPathSeparator)
        config.contentMappingConfig.urlEncode shouldBe true
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "initialize a correct download configuration" in {
    val c = createConfiguration()

    createArchiveConfig(c) match {
      case Success(config) =>
        config.downloadConfig should be(DownloadData)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "support alternative configuration paths" in {
    val Key = "an.alternative.path"
    val c = createConfiguration(Key)

    checkConfig(HttpArchiveConfig(c, Key, DownloadData, mock[HttpArchiveProtocol], TestAuthFunc))
  }

  it should "handle a prefix key that ends with a separator" in {
    val c = createConfiguration()

    checkConfig(HttpArchiveConfig(c, Prefix + '.', DownloadData, mock[HttpArchiveProtocol], TestAuthFunc))
  }

  /**
    * Helper method to test whether the archive name can be derived from the
    * archive URI.
    *
    * @param expName the expected name
    * @param uri     the URI to pass to the configuration
    */
  private def checkArchiveName(expName: String, uri: String = ArchiveUri): Unit = {
    val c = clearProperty(createConfiguration(), HttpArchiveConfig.PropArchiveName)
    c.setProperty(Prefix + "." + HttpArchiveConfig.PropArchiveUri, uri)

    createArchiveConfig(c) match {
      case Success(config) =>
        config.archiveName should be(expName)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "derive the archive name from the URI" in {
    checkArchiveName("music_archive_org_music_test_content")
  }

  it should "handle an archive name if the URI has no extension" in {
    checkArchiveName(uri = "http://archive.org/foo/bar/index",
      expName = "archive_org_foo_bar_index")
  }

  it should "set a default processor count if unspecified" in {
    val c = clearProperty(createConfiguration(), HttpArchiveConfig.PropProcessorCount)

    createArchiveConfig(c) match {
      case Success(config) =>
        config.processorCount should be(HttpArchiveConfig.DefaultProcessorCount)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "set a default processor timeout if unspecified" in {
    val c = clearProperty(createConfiguration(), HttpArchiveConfig.PropProcessorTimeout)

    createArchiveConfig(c) match {
      case Success(config) =>
        config.processorTimeout should be(HttpArchiveConfig.DefaultProcessorTimeout)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "set a default maximum content size if unspecified" in {
    val c = clearProperty(createConfiguration(), HttpArchiveConfig.PropMaxContentSize)

    createArchiveConfig(c) match {
      case Success(config) =>
        config.maxContentSize should be(HttpArchiveConfig.DefaultMaxContentSize)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "set a default download read chunk size if unspecified" in {
    val c = clearProperty(createConfiguration(), HttpArchiveConfig.PropDownloadReadChunkSize)

    createArchiveConfig(c) match {
      case Success(config) =>
        config.downloadReadChunkSize should be(HttpArchiveConfig.DefaultDownloadReadChunkSize)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "set a default propagation buffer size if none is specified" in {
    val c = clearProperty(createConfiguration(), HttpArchiveConfig.PropPropagationBufSize)

    createArchiveConfig(c) match {
      case Success(config) =>
        config.propagationBufSize should be(HttpArchiveConfig.DefaultPropagationBufSize)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "set a default request queue size if unspecified" in {
    val c = clearProperty(createConfiguration(), HttpArchiveConfig.PropRequestQueueSize)

    createArchiveConfig(c) match {
      case Success(config) =>
        config.requestQueueSize should be(HttpArchiveConfig.DefaultRequestQueueSize)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "set a default size for the cache for encrypted URIs if unspecified" in {
    val c = clearProperty(createConfiguration(), HttpArchiveConfig.PropCryptUriCacheSize)

    createArchiveConfig(c) match {
      case Success(config) =>
        config.cryptUriCacheSize should be(HttpArchiveConfig.DefaultCryptUriCacheSize)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "fail for an undefined archive URI" in {
    val c = clearProperty(createConfiguration(), HttpArchiveConfig.PropArchiveUri)

    createArchiveConfig(c) match {
      case Success(_) =>
        fail("Could read invalid config!")
      case Failure(e) =>
        e shouldBe a[IllegalArgumentException]
    }
  }

  it should "use defaults for the URI mapping config" in {
    val p = HttpArchiveConfig.PrefixMetaUriMapping
    val c = clearProperty(
      clearProperty(
        clearProperty(
          clearProperty(
            clearProperty(createConfiguration(), p + HttpArchiveConfig.PropMappingRemovePrefix),
            p + HttpArchiveConfig.PropMappingUriTemplate),
          p + HttpArchiveConfig.PropMappingPathSeparator),
        p + HttpArchiveConfig.PropMappingEncoding),
      p + HttpArchiveConfig.PropMappingRemoveComponents)

    createArchiveConfig(c) match {
      case Success(config) =>
        config.metaMappingConfig.removePrefix should be(null)
        config.metaMappingConfig.pathComponentsToRemove should be(0)
        config.metaMappingConfig.uriTemplate should be("${uri}")
        config.metaMappingConfig.pathSeparator should be(null)
        config.metaMappingConfig.urlEncode shouldBe false
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "use a default content URI mapping that does not manipulate URIs" in {
    val Uri = "/music/test-archive/media/Madonna1/playlist.settings"
    val mid = MediumID("someMedium", Some(Uri))
    val mapper = new UriMapper
    val config = checkConfig(HttpArchiveConfig(createConfiguration(), Prefix, DownloadData,
      mock[HttpArchiveProtocol], TestAuthFunc))

    mapper.mapUri(config.contentMappingConfig, mid, Uri) should be(Some(Uri))
  }

  it should "use a default value for the cookie management flag" in {
    val c = clearProperty(createConfiguration(), HttpArchiveConfig.PropNeedsCookieManagement)

    createArchiveConfig(c) match {
      case Success(config) =>
        config.needsCookieManagement shouldBe false
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "resolve a relative path" in {
    val FilePath = "/rock/song.mp3"
    val config = HttpArchiveConfig(createConfiguration(), Prefix, DownloadData, mock[HttpArchiveProtocol],
      TestAuthFunc).get

    val uri = config.resolvePath(FilePath)
    uri should be(Uri("https://music.archive.org/music/test" + FilePath))
  }
}
