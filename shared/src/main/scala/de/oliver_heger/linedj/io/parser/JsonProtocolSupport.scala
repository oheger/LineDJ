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

package de.oliver_heger.linedj.io.parser

import spray.json.*

/**
  * An object providing some helper functionality for dealing with JSON
  * serialization and deserialization.
  */
object JsonProtocolSupport:
  /**
    * Produces a [[RootJsonFormat]] for a type that supports a native
    * ''toString()'' conversion and a conversion function to convert strings
    * back to the type. This can be used for instance for ''enum'' types.
    *
    * @param c the conversion function from a string to the target type
    * @tparam T the target type
    * @return a [[RootJsonFormat]] to handle this type
    */
  def enumFormat[T](c: String => T): RootJsonFormat[T] = new RootJsonFormat[T]:
    override def read(json: JsValue): T = json match
      case JsString(value) => c(value)
      case _ => deserializationError("String expected with an enum literal.")

    override def write(obj: T): JsValue = JsString(obj.toString)
