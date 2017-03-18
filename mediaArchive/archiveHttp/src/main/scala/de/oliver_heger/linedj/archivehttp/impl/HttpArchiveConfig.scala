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
import org.apache.commons.configuration.Configuration

import scala.concurrent.duration._
import scala.util.Try

object HttpArchiveConfig {
  /**
    * The prefix for all configuration properties related to the HTTP archive.
    */
  val PropPrefix = "media.http."

  /** The configuration property for the archive URI. */
  val PropArchiveUri: String = PropPrefix + "archiveUri"

  /** The configuration property for the processor count. */
  val PropProcessorCount: String = PropPrefix + "processorCount"

  /** The configuration property for the processor timeout. */
  val PropProcessorTimeout: String = PropPrefix + "processorTimeout"

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
    * Tries to obtain a ''HttpArchiveConfig'' from the passed in
    * ''Configuration'' object. If mandatory parameters are missing, the
    * operation fails. Otherwise, a ''Success'' object is returned wrapping
    * the extracted ''HttpArchiveConfig'' instance.
    *
    * @param c the ''Configuration''
    * @return a ''Try'' with the extracted archive configuration
    */
  def apply(c: Configuration): Try[HttpArchiveConfig] = Try {
    val uri = c getString PropArchiveUri
    if (uri == null) {
      throw new IllegalArgumentException("No URI for HTTP archive configured!")
    }
    HttpArchiveConfig(c getString PropArchiveUri,
      c.getInt(PropProcessorCount, DefaultProcessorCount),
      if (c.containsKey(PropProcessorTimeout))
        Timeout(c.getInt(PropProcessorTimeout), TimeUnit.SECONDS)
      else DefaultProcessorTimeout)
  }
}

/**
  * A class defining the configuration settings to be applied for an HTTP
  * archive.
  *
  * @param archiveURI       the URI of the HTTP media archive
  * @param processorCount   the number of parallel processor actors to be used
  *                         when downloading meta data from the archive
  * @param processorTimeout the timeout for calls to processor actors
  */
case class HttpArchiveConfig(archiveURI: Uri, processorCount: Int,
                             processorTimeout: Timeout)
