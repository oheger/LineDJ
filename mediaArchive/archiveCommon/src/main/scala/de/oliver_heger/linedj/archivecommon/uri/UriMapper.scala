/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.archivecommon.uri

import java.util.regex.Pattern

import de.oliver_heger.linedj.shared.archive.media.{MediumID, UriHelper}

import scala.annotation.tailrec

object UriMapper {
  /** Constant for an empty medium path. */
  private val EmptyMediumPath = ""

  /**
    * Calculates the medium path based on the settings file of the given
    * medium ID. Can also handle an undefined settings file.
    *
    * @param mediumDescPath the path to the medium description file
    * @return the path to this medium
    */
  private def mediumPath(mediumDescPath: Option[String]): String =
    mediumDescPath map { d =>
      val filePos = d.lastIndexOf('/')
      if (filePos >= 0) d.substring(0, filePos) else EmptyMediumPath
    } getOrElse EmptyMediumPath

  /**
    * Applies the URI template from the given configuration to the specified
    * parameters.
    *
    * @param config         the mapping config
    * @param mediumDescPath the path to the medium description file
    * @param strippedUri    the URI with the prefix already removed
    * @return the resulting URI
    */
  private def applyUriTemplate(config: UriMappingSpec, mediumDescPath: Option[String],
                               strippedUri: String): String = {
    val result = config.uriTemplate.replace(UriMappingSpec.VarMediumPath, mediumPath(mediumDescPath))
      .replace(UriMappingSpec.VarUri,
        removePrefixComponents(config, urlEncode(config, strippedUri)))
    result
  }

  /**
    * Removes the prefix from the URI if possible. If a prefix is defined, but
    * the URI does not start with it, result is ''None''.
    *
    * @param prefix the prefix
    * @param uri    the URI
    * @return an Option for the URI with removed prefix
    */
  private def removePrefix(prefix: String, uri: String): Option[String] =
    if (prefix == null) Some(uri)
    else if (uri startsWith prefix) Some(uri substring prefix.length)
    else None

  /**
    * Applies URL encoding to the components of the specified URI string.
    *
    * @param config the mapping config
    * @param uri    the URI
    * @return the encoded URI
    */
  private def urlEncode(config: UriMappingSpec, uri: String): String =
    if (config.urlEncoding) {
      if (config.uriPathSeparator == null) encode(uri)
      else {
        val components = uri split Pattern.quote(config.uriPathSeparator)
        components.map(encode).mkString("/")
      }
    }
    else uri

  /**
    * Encodes the specified string.
    *
    * @param uri the URI string to be encoded
    * @return the encoded string
    */
  private def encode(uri: String): String = UriHelper urlEncode uri

  /**
    * Removes the configured number of path components from the beginning of
    * the given (encoded) URI.
    *
    * @param config the mapping config
    * @param uri    the URI
    * @return the URI with prefix components stripped off
    */
  private def removePrefixComponents(config: UriMappingSpec, uri: String): String = {
    @tailrec def removeComponent(currentUri: String, compCount: Int): String =
      if (compCount <= 0) currentUri
      else {
        val pos = currentUri indexOf '/'
        if (pos > 0 && pos < currentUri.length - 1)
          removeComponent(currentUri.substring(pos + 1), compCount - 1)
        else currentUri
      }

    val leadingSeparator = UriHelper.hasLeadingSeparator(uri)
    val uriToProcess = if (leadingSeparator) UriHelper.removeLeadingSeparator(uri)
    else uri
    val processedUri = removeComponent(uriToProcess, config.pathComponentsToRemove)
    if (leadingSeparator) UriHelper.withLeadingSeparator(processedUri)
    else processedUri
  }
}

/**
  * A class that processes URIs obtained from meta data files from an HTTP
  * archive.
  *
  * This class applies the data stored in a [[UriMappingSpec]] to URIs
  * encountered in meta data files.
  *
  * If an URI cannot be mapped, the mapping function returns an empty
  * option. In this case, the file is ignored.
  */
class UriMapper {

  import UriMapper._

  /**
    * Applies URI mapping to the specified URI of the given medium.
    *
    * @param config the mapping configuration
    * @param mid    the medium ID
    * @param uriOrg the original URI to be processed
    * @return an option for the processed URI
    */
  def mapUri(config: UriMappingSpec, mid: MediumID, uriOrg: String): Option[String] =
    mapUri(config, mid.mediumDescriptionPath, uriOrg)

  /**
    * Applies URI mapping to the specified URI.
    *
    * @param config         the mapping configuration
    * @param mediumDescPath the optional path to the medium description file
    * @param uriOrg         the original URI to be processed
    * @return an option for the processed URI
    */
  def mapUri(config: UriMappingSpec, mediumDescPath: Option[String], uriOrg: String):
  Option[String] =
    Option(uriOrg).flatMap(removePrefix(config.prefixToRemove, _))
      .map(u => applyUriTemplate(config, mediumDescPath, u))
}
