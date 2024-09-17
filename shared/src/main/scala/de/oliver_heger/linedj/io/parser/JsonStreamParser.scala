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

package de.oliver_heger.linedj.io.parser

import org.apache.pekko.stream.scaladsl.{Flow, JsonFraming, Keep, Source}
import org.apache.pekko.util.ByteString
import spray.json.*

/**
  * A module providing functionality for parsing (potentially large) JSON
  * sources.
  *
  * The module offers a function that accepts a [[Source]] to a JSON array. It
  * makes use of JSON deserialization provided by Spray Json to convert the 
  * single array elements to model objects.
  */
object JsonStreamParser:
  /**
    * The default value for the maximum length parameter passed to
    * [[JsonFraming]]. Clients can override this value if there is need.
    */
  final val DefaultMaxObjectLength = 8192
  
  /**
    * Parses a source with a JSON array and converts it to a source of model
    * objects.
    *
    * @param source          the source with JSON data
    * @param maxObjectLength the maximum length of a single object
    * @param reader          the [[JsonReader]] for the model objects
    * @tparam T   the type of the model objects
    * @tparam MAT the materialized type of the source
    * @return the [[Source]] for model objects
    */
  def parseStream[T, MAT](source: Source[ByteString, MAT],
                          maxObjectLength: Int = DefaultMaxObjectLength)
                         (using reader: JsonReader[T]): Source[T, MAT] =
    source.viaMat(JsonFraming.objectScanner(maxObjectLength))(Keep.left)
      .viaMat(Flow[ByteString].map { obj =>
        val jsonAst = obj.utf8String.parseJson
        jsonAst.convertTo(reader)
      })(Keep.left)
