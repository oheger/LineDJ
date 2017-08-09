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
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object HttpArchiveConfigSpec {
  /** The URI to the HTTP archive. */
  private val ArchiveUri = "https://music.archive.org/content.json"

  /** An object with test user credentials. */
  private val Credentials = UserCredentials("scott", "tiger")

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

  /** The maximum content size value. */
  private val MaxContentSize = 10

  /** The download buffer size value. */
  private val DownloadBufferSize = 16384

  /** The inactivity timeout for download operations. */
  private val DownloadMaxInactivity = 10.minutes

  /** The read chunk size for download operations. */
  private val DownloadReadChunkSize = 8000

  /** The read chunk to apply when a timeout occurs. */
  private val TimeoutReadChunkSize = 6000

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
    c.addProperty(at + ".maxContentSize", MaxContentSize)
    c.addProperty(at + ".downloadBufferSize", DownloadBufferSize)
    c.addProperty(at + ".downloadMaxInactivity", DownloadMaxInactivity.toSeconds)
    c.addProperty(at + ".downloadReadChunkSize", DownloadReadChunkSize)
    c.addProperty(at + ".timeoutReadChunkSize", TimeoutReadChunkSize)
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
class HttpArchiveConfigSpec extends FlatSpec with Matchers {

  import HttpArchiveConfigSpec._

  /**
    * Checks whether the specified configuration contains the expected
    * default properties.
    *
    * @param triedConfig a ''Try'' for the config to check
    */
  private def checkConfig(triedConfig: Try[HttpArchiveConfig]): Unit = {
    triedConfig match {
      case Success(config) =>
        config.archiveURI should be(Uri(ArchiveUri))
        config.archiveName should be(ArchiveName)
        config.processorCount should be(ProcessorCount)
        config.processorTimeout should be(Timeout(ProcessorTimeout, TimeUnit.SECONDS))
        config.maxContentSize should be(MaxContentSize)
        config.downloadBufferSize should be(DownloadBufferSize)
        config.downloadReadChunkSize should be(DownloadReadChunkSize)
        config.downloadMaxInactivity should be(DownloadMaxInactivity)
        config.timeoutReadChunkSize should be(TimeoutReadChunkSize)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  "An HttpArchiveConfig" should "process a valid configuration" in {
    val c = createConfiguration()

    checkConfig(HttpArchiveConfig(c, Prefix, Credentials, DownloadData))
  }

  it should "initialize a correct download configuration" in {
    val c = createConfiguration()

    HttpArchiveConfig(c, Prefix, Credentials, DownloadData) match {
      case Success(config) =>
        config.downloadConfig should be(DownloadData)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "support alternative configuration paths" in {
    val Key = "an.alternative.path"
    val c = createConfiguration(Key)

    checkConfig(HttpArchiveConfig(c, Key, Credentials, DownloadData))
  }

  it should "handle a prefix key that ends with a separator" in {
    val c = createConfiguration()

    checkConfig(HttpArchiveConfig(c, Prefix + '.', Credentials, DownloadData))
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

    HttpArchiveConfig(c, Prefix, Credentials, DownloadData) match {
      case Success(config) =>
        config.archiveName should be(expName)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "derive the archive name from the URI" in {
    checkArchiveName("music_archive_org_content")
  }

  it should "handle an archive name if the URI has no extension" in {
    checkArchiveName(uri = "http://archive.org/foo/bar/index",
      expName = "archive_org_foo_bar_index")
  }

  it should "set a default processor count if unspecified" in {
    val c = clearProperty(createConfiguration(), HttpArchiveConfig.PropProcessorCount)

    HttpArchiveConfig(c, Prefix, Credentials, DownloadData) match {
      case Success(config) =>
        config.processorCount should be(HttpArchiveConfig.DefaultProcessorCount)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "set a default processor timeout if unspecified" in {
    val c = clearProperty(createConfiguration(), HttpArchiveConfig.PropProcessorTimeout)

    HttpArchiveConfig(c, Prefix, Credentials, DownloadData) match {
      case Success(config) =>
        config.processorTimeout should be(HttpArchiveConfig.DefaultProcessorTimeout)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "set a default maximum content size if unspecified" in {
    val c = clearProperty(createConfiguration(), HttpArchiveConfig.PropMaxContentSize)

    HttpArchiveConfig(c, Prefix, Credentials, DownloadData) match {
      case Success(config) =>
        config.maxContentSize should be(HttpArchiveConfig.DefaultMaxContentSize)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "set a default download read chunk size if unspecified" in {
    val c = clearProperty(createConfiguration(), HttpArchiveConfig.PropDownloadReadChunkSize)

    HttpArchiveConfig(c, Prefix, Credentials, DownloadData) match {
      case Success(config) =>
        config.downloadReadChunkSize should be(HttpArchiveConfig.DefaultDownloadReadChunkSize)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "set a default timeout read chunk size if unspecified" in {
    val c = clearProperty(createConfiguration(), HttpArchiveConfig.PropTimeoutReadChunkSize)

    HttpArchiveConfig(c, Prefix, Credentials, DownloadData) match {
      case Success(config) =>
        config.timeoutReadChunkSize should be(HttpArchiveConfig.DefaultDownloadReadChunkSize)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "fail for an undefined archive URI" in {
    val c = clearProperty(createConfiguration(), HttpArchiveConfig.PropArchiveUri)

    HttpArchiveConfig(c, Prefix, Credentials, DownloadData) match {
      case Success(_) =>
        fail("Could read invalid config!")
      case Failure(e) =>
        e shouldBe a[IllegalArgumentException]
    }
  }
}
