/*
 * Copyright 2015-2022 The Developers Team.
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

object UriMappingSpec {
  /**
    * Variable representing the path to a medium in a URI template.
    */
  val VarMediumPath: String = "${medium}"

  /**
    * Variable representing the processed URI string in a URI template.
    */
  val VarUri: String = "${uri}"
}

/**
  * A trait defining how a URI has to be mapped.
  *
  * Archives with media files sometimes have to do certain transformations on
  * URIs. For instance, if URIs are defined to represent local paths, they may
  * have to be adapted when exposed from a union archive. This trait allows a
  * generic description of such transformation steps. This package contains a
  * helper class that can execute these steps.
  *
  * URI mapping uses strings as input. The basic idea is that the string can
  * have a prefix which needs to be removed. This is typically an absolute path
  * prefix which is not needed if the URI should be used in a relative context.
  *
  * The remaining string can be optionally URL-encoded. This is useful if it
  * represents a file on the local file system and should later be accessible
  * via an HTTP request. Then some further transformations can be applied, e.g.
  * adding another prefix or inserting additional components. This is defined
  * by a template which can contain a number of placeholders. See the
  * documentation for the single properties defined by this trait.
  */
trait UriMappingSpec {
  /**
    * Defines a prefix to be removed from a URI. When doing the mapping it is
    * expected that all URIs start with this prefix - other URIs are ignored.
    * The prefix is then removed, so that the remaining part can be further
    * processed, e.g. concatenated to the root path of the current medium. If
    * this property is undefined, no prefix is removed.
    *
    * @return an optional prefix to be removed
    */
  def prefixToRemove: String

  /**
    * The number of (prefix) path components to remove from a URI (after the
    * prefix has been removed). A file URI may have some components in common
    * with the root path of the owning medium. When constructing the final URI
    * based on the ''uriTemplate'' it can therefore be necessary to cut off
    * this common prefix. If this property has a value greater than 0, then the
    * given number of path components is removed from the beginning of the
    * URL-encoded URI string. (If the string has less components, the last one
    * is returned.)
    *
    * @return the number of path components to be removed
    */
  def pathComponentsToRemove: Int

  /**
    * The configuration property defining the template to be applied for URI
    * mapping. This template defines how resulting URIs look like. It is an
    * arbitrary string which can contain a few number of variables. The
    * variables are replaced by current values obtained during URI processing.
    * The following variables are supported:
    *
    *  - ''${medium}'' the root path to the medium the current file belongs to
    *  - ''${uri}'' the processed URI of the file
    *
    * For instance, the expression ''/test${medium}/${uri}'' generates relative
    * URIs (resolved against the root URI of an HTTP archive) pointing to
    * files below a path ''test'' that are structured in directories
    * corresponding to their media.
    *
    * @return the template for URI generation
    */
  def uriTemplate: String

  /**
    * The configuration property that controls the URL encoding of URIs. If
    * set to '''true''', the single components of URIs are encoded. (As they
    * might contain path separators, those separators are treated in a special
    * way.)
    *
    * @return a flag whether URL encoding should be applied
    */
  def urlEncoding: Boolean

  /**
    * The configuration property defining the path separator used within URIs.
    * This is evaluated if the ''urlEncoding'' flag is set. Then URIs are split
    * at this separator, and the single components are encoded. If no separator
    * is provided, URL encoding is done on the whole URI string.
    *
    * @return a string defining the path separators used in URIs
    */
  def uriPathSeparator: String
}
