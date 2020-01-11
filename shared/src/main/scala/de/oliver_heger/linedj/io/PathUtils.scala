/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.io

import java.nio.file.{Path, Paths}

import scala.language.implicitConversions

/**
  * A class providing some utility functions related to ''Path'' objects.
  */
object PathUtils {

  /**
    * Constant for a string that is returned by ''extractExtension()'' if a
    * file does not have an extension. This is an empty string.
    */
  val NoExtension = ""

  /**
    * Extracts a file extension from the given path. If the file does not have
    * an extension, result is an empty string (as specified by the
    * ''NoExtension'' constant).
    *
    * @param path the path
    * @return the file extension
    */
  def extractExtension(path: String): String = {
    val pos = path lastIndexOf '.'
    if (pos >= 0) path.substring(pos + 1)
    else NoExtension
  }

  /**
    * Converts the specified string to a ''Path''. The string is taken as is
    * and transformed to a path. This can also be used for implicit
    * conversions.
    *
    * @param s the string to be converted
    * @return the resulting ''Path''
    */
  implicit def asPath(s: String): Path = Paths get s
}
