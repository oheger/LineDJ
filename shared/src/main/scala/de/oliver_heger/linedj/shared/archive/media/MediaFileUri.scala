/*
 * Copyright 2015-2026 The Developers Team.
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

/**
  * A data class to represent the URI of a media file.
  *
  * URIs are just plain strings; but this value class enriches the type system.
  *
  * @param uri the actual URI of the media file
  */
case class MediaFileUri(uri: String) extends AnyVal:
  /**
    * Extracts the name of this URI, which is the last path component with the
    * extension removed.
    *
    * @return the name of this URI
    */
  def name: String = UriHelper.removeExtension(UriHelper.urlDecode(UriHelper.extractName(uri)))

  /**
    * Returns the relative path represented by this URI. This is basically the
    * URL-decode version of this URI.
    *
    * @return the path for this URI
    */
  def path: String = UriHelper.removeLeadingSeparator(UriHelper.decodeComponents(uri))
