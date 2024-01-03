/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.archive.metadata.persistence

import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData

object MetaDataJsonConverter:
  /**
    * Surrounds the specified string with quotation marks.
    *
    * @param s the string
    * @return the quoted string
    */
  private def quoteStr(s: String): String = s"\"${s.replace('"', '\'')}\""

/**
  * A converter class for transforming media meta data to JSON strings.
  *
  * This converter is used when writing media meta data to disk. The generated
  * files use JSON syntax. The meta data for each song is represented by a
  * JSON object. This converter generates the JSON representation for a single
  * song.
  */
class MetaDataJsonConverter:

  import MetaDataJsonConverter._

  /**
    * Generates a JSON representation for the given metadata.
    *
    * @param uri  the URI of the associated file
    * @param data the metadata for this file
    * @return a string with the JSON representation of this data
    */
  def convert(uri: String, data: MediaMetaData): String =

    // Appends the specified property to the string builder
    def append(props: List[String], property: String, value: String, quote: Boolean):
    List[String] =
      val buf = new java.lang.StringBuilder(64)
      buf.append(quoteStr(property)).append(": ")
      val strValue = String.valueOf(value)
      buf.append(if quote then quoteStr(strValue) else strValue)
      buf.toString :: props

    // Optionally appends a value to the string builder
    def appendOpt[V](props: List[String], property: String, value: Option[V], quote: Boolean):
    List[String] =
      value match
        case Some(v) =>
          append(props, property, String.valueOf(v), quote)
        case None =>
          props

    val props = appendOpt(appendOpt(appendOpt(append(append(appendOpt(appendOpt(appendOpt(appendOpt(
      Nil, "inceptionYear", data.inceptionYear, quote = false),
      "trackNumber", data.trackNumber, quote = false),
      "duration", data.duration, quote = false),
      "formatDescription", data.formatDescription, quote = true),
      "size", data.size.toString, quote = false),
      "uri", uri, quote = true),
      "title", data.title, quote = true),
      "artist", data.artist, quote = true),
      "album", data.album, quote = true)
    props.mkString("{\n", ",\n", "}\n")
