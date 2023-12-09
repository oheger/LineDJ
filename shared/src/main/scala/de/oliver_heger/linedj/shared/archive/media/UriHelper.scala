/*
 * Copyright 2015-2023 The Developers Team.
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
import scala.collection.immutable.Seq

/**
  * An object providing helper functions for dealing with URIs.
  *
  * Media files in an archive are normally represented by URIs. For some
  * operations, e.g. traversal, validation, or display to the user, such URIs
  * have to be manipulated. This object provides functions that implement
  * typical operations on URI strings.
  */
object UriHelper:
  /** A separator character for URIs. */
  final val UriSeparatorChar = '/'

  /** The URI separator as string. */
  final val UriSeparator: String = UriSeparatorChar.toString

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
  def normalize(uri: String): String = uri.replace(BackSlash, UriSeparatorChar)

  /**
    * Removes the file extension of the given URI if there is any. Otherwise,
    * the URI is returned without changes. Note that this function expects that
    * the URI is normalized.
    *
    * @param uri the URI
    * @return the URI with a file extension removed
    */
  def removeExtension(uri: String): String =
    val posLastComponent = uri lastIndexOf UriSeparator
    val posExt = uri lastIndexOf Ext
    if posExt > 0 && (posLastComponent < 0 || posExt > posLastComponent) then uri.substring(0, posExt) else uri

  /**
    * Removes the given character from the string if it is the last one. If
    * the string does not end with this character, it is not changed.
    *
    * @param s the string
    * @param c the character to remove
    * @return the resulting string
    */
  @tailrec def removeTrailing(s: String, c: String): String =
    if s.endsWith(c) then removeTrailing(s.dropRight(c.length), c)
    else s

  /**
    * Removes the given prefix from the string if it exists. If the string does
    * not start with this prefix, it is not changed.
    *
    * @param s      the string
    * @param prefix the prefix to remove
    * @return the resulting string
    */
  @tailrec def removeLeading(s: String, prefix: String): String =
    if s startsWith prefix then removeLeading(s.substring(prefix.length), prefix)
    else s

  /**
    * Removes a trailing separator character from the given URI. If the URI
    * does not end with a separator, it is returned without changes.
    *
    * @param uri the URI
    * @return the URI with a trailing separator removed
    */
  def removeTrailingSeparator(uri: String): String =
    removeTrailing(uri, UriSeparator)

  /**
    * Removes a leading separator from the given URI if it is present.
    * Otherwise, the URI is returned as is.
    *
    * @param uri the URI
    * @return the URI with a leading separator removed
    */
  def removeLeadingSeparator(uri: String): String =
    removeLeading(uri, UriSeparator)

  /**
    * Makes sure that the passed in URI ends with a separator. A separator is
    * added if and only if the passed in string does not already end with one.
    *
    * @param uri the URI to be checked
    * @return the URI ending with a separator
    */
  def withTrailingSeparator(uri: String): String =
    if hasTrailingSeparator(uri) then uri else uri + UriSeparator

  /**
    * Returns a flag whether the passed in URI string ends with a separator
    * character.
    *
    * @param uri the URI to be checked
    * @return '''true''' if the URI ends with a separator; '''false'''
    *         otherwise
    */
  def hasTrailingSeparator(uri: String): Boolean = uri.endsWith(UriSeparator)

  /**
    * Makes sure that the passed in URI starts with a separator. A separator is
    * added if and only if the passed in string does not already start with
    * one.
    *
    * @param uri the URI to be checked
    * @return the URI starting with a separator
    */
  def withLeadingSeparator(uri: String): String =
    if hasLeadingSeparator(uri) then uri else UriSeparator + uri

  /**
    * Returns a flag whether the passed in URI string starts with a separator
    * character.
    *
    * @param uri the URI to be checked
    * @return '''true''' if the URI starts with a separator; '''false'''
    *         otherwise
    */
  def hasLeadingSeparator(uri: String): Boolean = uri.startsWith(UriSeparator)

  /**
    * Checks whether the given URI has a parent element. If this function
    * returns '''false''' the URI points to a top-level element in the
    * iteration.
    *
    * @param uri the URI in question
    * @return a flag whether this URI has a parent element
    */
  def hasParent(uri: String): Boolean =
    findNameComponentPos(uri) > 0

  /**
    * Splits the given URI in a parent URI and a name. This function
    * determines the position of the last name component in the given URI. The
    * URI is split at this position, and both strings are returned. The
    * separator is not contained in any of these components, i.e. the parent
    * URI does not end with a separator nor does the name start with one. If
    * the URI has no parent, the resulting parent string is empty.
    *
    * @param uri the URI to be split
    * @return a tuple with the parent URI and the name component
    */
  def splitParent(uri: String): (String, String) =
    val canonicalUri = removeTrailingSeparator(uri)
    val pos = findNameComponentPos(canonicalUri)
    if pos >= 0 then (canonicalUri.substring(0, pos), canonicalUri.substring(pos + 1))
    else ("", canonicalUri)

  /**
    * Returns the name component (the text behind the last '/' character) of
    * the given URI. If the URI does not contain any slashes, it is returned
    * without changes. Note that this function expects that the URI is
    * normalized.
    *
    * @param uri the URI
    * @return the name component of this URI
    */
  def extractName(uri: String): String =
    val posLastComponent = findNameComponentPos(uri)
    if posLastComponent >= 0 then uri.substring(posLastComponent + 1) else uri

  /**
    * Returns the parent of the given URI. This is the URI with the name
    * component stripped. If the URI consists only of a name component, result
    * is an empty string. Note that this function expects that the URI is
    * normalized.
    *
    * @param uri the URI
    * @return the parent URI
    */
  def extractParent(uri: String): String =
    val posLastComponent = findNameComponentPos(uri)
    if posLastComponent >= 0 then uri.substring(0, posLastComponent) else ""

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
    if uri1.nonEmpty then uri1 + UriSeparator + uri2
    else uri2

  /**
    * Performs URL decoding on the given URI if it is URL encoded. Otherwise,
    * the URI is return without changes.
    *
    * @param uri the URI
    * @return the decoded URI
    */
  def urlDecode(uri: String): String =
    if isUrlEncoded(uri) then
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
  def isUrlEncoded(uri: String): Boolean =
    def illegalEncoding(c: Char): Boolean =
      val digit = c.toLower
      (digit < '0' || digit > '9') && (digit < 'a' || digit > 'f')

    @tailrec def checkEncoding(index: Int): Boolean =
      val nextPos = uri.indexOf('%', index)
      if nextPos < 0 then true
      else if nextPos >= uri.length - 2 || illegalEncoding(uri.charAt(nextPos + 1)) ||
        illegalEncoding(uri.charAt(nextPos + 2)) then false
      else checkEncoding(nextPos + 3)

    checkEncoding(0)

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

  /**
    * Splits the given URI into its components separated by the URI separator.
    *
    * @param uri the URI to be split
    * @return an array with the single components
    */
  def splitComponents(uri: String): Array[String] =
    removeLeadingSeparator(uri) split UriSeparator

  /**
    * Creates a URI string from the given components. The components are
    * combined using the URI separator.
    *
    * @param components the sequence with components
    * @return the resulting URI
    */
  def fromComponents(components: Seq[String]): String =
    UriSeparator + components.mkString(UriSeparator)

  /**
    * Transforms a URI by applying the given mapping function to all its
    * components. The URI is split into components, then the function is
    * executed on each component, and finally the components are combined
    * again.
    *
    * @param uri the URI
    * @param f   the mapping function for components
    * @return the resulting URI
    */
  def mapComponents(uri: String)(f: String => String): String =
    val components = splitComponents(uri).toSeq
    val mappedComponents = components map f
    fromComponents(mappedComponents)

  /**
    * Encodes all the components of the given URI. Note that it is typically
    * not possible to encode the URI as a whole because then the separators
    * will be encoded as well. This function splits the URI into its components
    * first, then applies the encoding, and finally combines the parts to the
    * resulting URI.
    *
    * @param uri the URI
    * @return the URI with its components encoded
    */
  def encodeComponents(uri: String): String =
    mapComponents(uri)(urlEncode)

  /**
    * Decodes all the components of the given URI. Works like
    * ''encodeComponents()'', but applies decoding to the single components.
    *
    * @param uri the URI
    * @return the URI with its components decoded
    */
  def decodeComponents(uri: String): String =
    mapComponents(uri)(urlDecode)

  /**
    * Searches for the position of the name component in the given URI. If
    * found, its index is returned; otherwise, result is -1.
    *
    * @param uri the URI
    * @return the position of the name component or -1
    */
  private def findNameComponentPos(uri: String): Int =
    uri lastIndexOf UriSeparatorChar
