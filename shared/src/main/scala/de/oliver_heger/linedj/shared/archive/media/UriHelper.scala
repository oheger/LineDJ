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

package de.oliver_heger.linedj.shared.archive.media

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets

import scala.annotation.tailrec

/**
  * An object providing helper functions for dealing with URIs.
  *
  * Media files in an archive are normally represented by URIs. For some
  * operations, e.g. traversal, validation, or display to the user, such URIs
  * have to be manipulated. This object provides functions that implement
  * typical operations on URI strings.
  */
object UriHelper {
  /** A separator character for URIs. */
  private val UriSeparator = '/'

  /** The backslash character. */
  private val BackSlash = '\\'

  /** Constant for the extension character. */
  private val Ext = '.'

  /** The default encoding of a space used by URLEncoder. */
  private val DefSpaceEncoding = "+"

  /** The encoding for a space that is preferred. */
  private val PreferredSpaceEncoding = "%20"

  /**
    * Returns a normalized form of the given URI. The resulting URI is
    * guaranteed to use the slash ('/') as separator character.
    *
    * @param uri the URI
    * @return the normalized URI
    */
  def normalize(uri: String): String = uri.replace(BackSlash, UriSeparator)

  /**
    * Removes the file extension of the given URI if there is any. Otherwise,
    * the URI is returned without changes. Note that this function expects that
    * the URI is normalized.
    *
    * @param uri the URI
    * @return the URI with a file extension removed
    */
  def removeExtension(uri: String): String = {
    val posLastComponent = uri lastIndexOf UriSeparator
    val posExt = uri lastIndexOf Ext
    if (posExt > 0 && (posLastComponent < 0 || posExt > posLastComponent)) uri.substring(0, posExt) else uri
  }

  /**
    * Removes a trailing separator character from the given URI. If the URI
    * does not end with a separator, it is returned without changes.
    *
    * @param uri the URI
    * @return the URI with a trailing separator removed
    */
  def removeTrailingSeparator(uri: String): String =
    if (uri endsWith UriSeparator.toString) uri.substring(0, uri.length - 1)
    else uri

  /**
    * Returns the name component (the text behind the last '/' character) of
    * the given URI. If the URI does not contain any slashes, it is returned
    * without changes. Note that this function expects that the URI is
    * normalized.
    *
    * @param uri the URI
    * @return the name component of this URI
    */
  def extractName(uri: String): String = {
    val posLastComponent = uri lastIndexOf UriSeparator
    if (posLastComponent >= 0) uri.substring(posLastComponent + 1) else uri
  }

  /**
    * Returns the parent of the given URI. This is the URI with the name
    * component stripped. If the URI consists only of a name component, result
    * is an empty string. Note that this function expects that the URI is
    * normalized.
    *
    * @param uri the URI
    * @return the parent URI
    */
  def extractParent(uri: String): String = {
    val posLastComponent = uri lastIndexOf UriSeparator
    if (posLastComponent >= 0) uri.substring(0, posLastComponent) else ""
  }

  /**
    * Concatenates two URI components. If both components are defined, they
    * are concatenated using a slash as separator. Both components can be empty
    * and are then treated accordingly.
    *
    * @param uri1 the first URI component
    * @param uri2 the second URI component
    * @return the concatenated URI
    */
  def concat(uri1: String, uri2: String): String =
    if (uri1.nonEmpty) uri1 + UriSeparator + uri2
    else uri2

  /**
    * Performs URL decoding on the given URI if it is URL encoded. Otherwise,
    * the URI is return without changes.
    *
    * @param uri the URI
    * @return the decoded URI
    */
  def urlDecode(uri: String): String =
    if (isUrlEncoded(uri))
      URLDecoder.decode(uri, StandardCharsets.UTF_8.name())
    else uri

  /**
    * Checks if the given URI is URL encoded. Result is '''true''' if and only
    * if the URI contains at least one '%' character, and all occurrences of
    * '%' characters are followed by valid codes.
    *
    * @param uri the string to be tested
    * @return a flag whether this string is URL encoded
    */
  def isUrlEncoded(uri: String): Boolean = {
    def illegalEncoding(c: Char): Boolean = {
      val digit = c.toLower
      (digit < '0' || digit > '9') && (digit < 'a' || digit > 'f')
    }

    @tailrec def checkEncoding(index: Int): Boolean = {
      val nextPos = uri.indexOf('%', index)
      if (nextPos < 0) true
      else if (nextPos >= uri.length - 2 || illegalEncoding(uri.charAt(nextPos + 1)) ||
        illegalEncoding(uri.charAt(nextPos + 2))) false
      else checkEncoding(nextPos + 3)
    }

    checkEncoding(0)
  }

  /**
    * Returns a URL-encoded version of the given URI. This function differs
    * from the normal result produced by ''URLEncoder'' in treating the space
    * character differently: instead of a '+' character, it is encoded as
    * '%20'.
    *
    * @param uri the URI
    * @return the encoded URI
    */
  def urlEncode(uri: String): String =
    URLEncoder.encode(uri, StandardCharsets.UTF_8.name())
      .replace(DefSpaceEncoding, PreferredSpaceEncoding)
}