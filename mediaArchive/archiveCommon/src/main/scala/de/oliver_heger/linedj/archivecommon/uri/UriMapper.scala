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

package de.oliver_heger.linedj.archivecommon.uri

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import de.oliver_heger.linedj.shared.archive.media.MediumID

object UriMapper {
  /** Constant for an empty medium path. */
  private val EmptyMediumPath = ""

  /**
    * Calculates the medium path based on the settings file of the given
    * medium ID. Can also handle an undefined settings file.
    *
    * @param mid the medium ID
    * @return the path to this medium
    */
  private def mediumPath(mid: MediumID): String =
    mid.mediumDescriptionPath map { d =>
      val filePos = d.lastIndexOf('/')
      if (filePos >= 0) d.substring(0, filePos) else EmptyMediumPath
    } getOrElse EmptyMediumPath

  /**
    * Applies the URI template from the given configuration to the specified
    * parameters.
    *
    * @param config      the mapping config
    * @param mid         the medium ID
    * @param strippedUri the URI with the prefix already removed
    * @return the resulting URI
    */
  private def applyUriTemplate(config: UriMappingSpec, mid: MediumID, strippedUri: String):
  String =
    config.uriTemplate.replace(UriMappingSpec.VarMediumPath, mediumPath(mid))
      .replace(UriMappingSpec.VarUri, urlEncode(config, strippedUri))

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
        val components = uri split config.uriPathSeparator
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
  private def encode(uri: String): String =
    URLEncoder.encode(uri, StandardCharsets.UTF_8.name()).replace("+", "%20")
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
    * Applies URI mapping to the specified URI.
    *
    * @param config the mapping configuration
    * @param mid    the medium ID
    * @param uriOrg the original URI to be processed
    * @return an option for the processed URI
    */
  def mapUri(config: UriMappingSpec, mid: MediumID, uriOrg: String): Option[String] =
    Option(uriOrg).flatMap(removePrefix(config.prefixToRemove, _))
      .map(u => applyUriTemplate(config, mid, u))
}
