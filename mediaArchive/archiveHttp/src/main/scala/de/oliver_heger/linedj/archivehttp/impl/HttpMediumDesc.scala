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

/**
  * Data class representing a description of a medium in an HTTP archive.
  *
  * The URL identifying an HTTP archive points to a JSON file with a list of
  * the media available in this archive. Each element in this document
  * references a medium, consisting of the path to the medium description file
  * and the meta data file. Paths are relative URLs to the root URL of the
  * archive.
  *
  * This class represents one element of this description document.
  *
  * @param mediumDescriptionPath the path to the medium description file
  * @param metaDataPath          the path to the meta data file
  */
case class HttpMediumDesc(mediumDescriptionPath: String, metaDataPath: String)
