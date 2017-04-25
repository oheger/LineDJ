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
  val PropArchiveUri: String = PropPrefix + "archiveUri"

  /** The configuration property for the processor count. */
  val PropProcessorCount: String = PropPrefix + "processorCount"

  /** The configuration property for the processor timeout. */
  val PropProcessorTimeout: String = PropPrefix + "processorTimeout"

  /** The configuration property for the maximum size of a content file. */
  val PropMaxContentSize: String = PropPrefix + "maxContentSize"

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
    * Tries to obtain a ''HttpArchiveConfig'' from the passed in
    * ''Configuration'' object. If mandatory parameters are missing, the
    * operation fails. Otherwise, a ''Success'' object is returned wrapping
    * the extracted ''HttpArchiveConfig'' instance. Note that user credentials
    * have to be provided separately; it is typically not an option to store
    * credentials as plain text in a configuration file.
    *
    * @param c the ''Configuration''
    * @param credentials user credentials
    * @return a ''Try'' with the extracted archive configuration
    */
  def apply(c: Configuration, credentials: UserCredentials): Try[HttpArchiveConfig] = Try {
    val uri = c getString PropArchiveUri
    if (uri == null) {
      throw new IllegalArgumentException("No URI for HTTP archive configured!")
    }
    HttpArchiveConfig(c getString PropArchiveUri, credentials,
      c.getInt(PropProcessorCount, DefaultProcessorCount),
      if (c.containsKey(PropProcessorTimeout))
        Timeout(c.getInt(PropProcessorTimeout), TimeUnit.SECONDS)
      else DefaultProcessorTimeout,
      c.getInt(PropMaxContentSize, DefaultMaxContentSize))
  }
}

/**
  * A class defining the configuration settings to be applied for an HTTP
  * archive.
  *
  * @param archiveURI       the URI of the HTTP media archive
  * @param credentials      credentials to connect to the archive
  * @param processorCount   the number of parallel processor actors to be used
  *                         when downloading meta data from the archive
  * @param processorTimeout the timeout for calls to processor actors
  * @param maxContentSize   the maximum size of a content file (either a
  *                         settings or a meta data file) in kilobytes; if a
  *                         file is larger, it is canceled
  */
case class HttpArchiveConfig(archiveURI: Uri, credentials: UserCredentials,
                             processorCount: Int,
                             processorTimeout: Timeout,
                             maxContentSize: Int)