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

package de.oliver_heger.linedj.archivehttp.impl

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.scalatest.{FlatSpec, Matchers}

import scala.util.{Failure, Success}

object HttpArchiveConfigSpec {
  /** The URI to the HTTP archive. */
  private val ArchiveUri = "https://music.archive.org/content.json"

  /** The number of processors. */
  private val ProcessorCount = 8

  /** The timeout for processor actors. */
  private val ProcessorTimeout = 60

  /**
    * Creates a configuration object with all test settings.
    *
    * @return the test configuration
    */
  private def createConfiguration(): Configuration = {
    val c = new PropertiesConfiguration
    c.addProperty("media.http.archiveUri", ArchiveUri)
    c.addProperty("media.http.processorCount", ProcessorCount)
    c.addProperty("media.http.processorTimeout", ProcessorTimeout)
    c
  }
}

/**
  * Test class for ''HttpArchiveConfig''.
  */
class HttpArchiveConfigSpec extends FlatSpec with Matchers {

  import HttpArchiveConfigSpec._

  "An HttpArchiveConfig" should "process a valid configuration" in {
    val c = createConfiguration()

    HttpArchiveConfig(c) match {
      case Success(config) =>
        config.archiveURI should be(Uri(ArchiveUri))
        config.processorCount should be(ProcessorCount)
        config.processorTimeout should be(Timeout(ProcessorTimeout, TimeUnit.SECONDS))
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "set a default processor count if unspecified" in {
    val c = createConfiguration()
    c clearProperty HttpArchiveConfig.PropProcessorCount

    HttpArchiveConfig(c) match {
      case Success(config) =>
        config.processorCount should be(HttpArchiveConfig.DefaultProcessorCount)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "set a default processor timeout if unspecified" in {
    val c = createConfiguration()
    c clearProperty HttpArchiveConfig.PropProcessorTimeout

    HttpArchiveConfig(c) match {
      case Success(config) =>
        config.processorTimeout should be(HttpArchiveConfig.DefaultProcessorTimeout)
      case Failure(e) =>
        fail("Unexpected exception: " + e)
    }
  }

  it should "fail for an undefined archive URI" in {
    val c = createConfiguration()
    c clearProperty HttpArchiveConfig.PropArchiveUri

    HttpArchiveConfig(c) match {
      case Success(_) =>
        fail("Could read invalid config!")
      case Failure(e) =>
        e shouldBe a[IllegalArgumentException]
    }
  }
}
