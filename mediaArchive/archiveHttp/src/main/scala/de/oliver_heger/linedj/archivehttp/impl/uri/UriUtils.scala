package de.oliver_heger.linedj.archivehttp.impl.uri

import akka.http.scaladsl.model.Uri

/**
  * A class providing utility functions related to URI handling.
  */
object UriUtils {
  /** The character sequence that separates the scheme from the host. */
  private val SchemeSeparator = "://"

  /**
    * A character sequence that replaces the scheme separator, so that it is
    * not affected by a split operation and can be detected.
    */
  private val SchemeReplacement = ":::"

  /**
    * Splits the specified URI into its path components.
    *
    * @param uri the URI
    * @return a sequence with the single path components
    */
  def uriComponents(uri: String): Seq[String] =
    uri.replace(SchemeSeparator, SchemeReplacement)
      .split("(^[:/])?/").dropWhile(s => s.length < 1 || s.contains(SchemeReplacement))

  /**
    * Calculates a URI based on the given one that is relative to the
    * specified base components. The passed in URI is split into its
    * components, and the common prefix with the base components is removed.
    * From the remaining path components a string is constructed.
    *
    * @param baseComponents a sequence with the components of the base URI
    * @param uri            the URI in question
    * @return a string with the URI relative to the base components
    */
  def relativeUri(baseComponents: Seq[String], uri: String): String = {
    val components = uriComponents(uri)
    baseComponents.zipAll(components, "", "")
      .dropWhile(t => t._1 == t._2)
      .map(_._2)
      .mkString("/")
  }

  /**
    * Resolves a relative URI string against the given base URI. This function
    * interprets the passed in URI string as a relative URI. (If it starts with
    * a slash, this slash is removed to force that it is treated as relative.)
    * The path of the URI is then appended to the path of the base URI.
    *
    * @param baseUri the base URI
    * @param uri     the (relative) URI to be resolved
    * @return the resulting URI
    */
  def resolveUri(baseUri: Uri, uri: String): Uri =
    Uri(uri).resolvedAgainst(baseUri).toRelative
}
