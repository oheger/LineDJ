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

package de.oliver_heger.linedj.pleditor.spi

import java.util.{Locale, ResourceBundle}

object LocalizedPlaylistReorderer {
  /**
    * The resource key for the name property. This key is looked up in the
    * resource bundle in order to determine the name of the reorder
    * algorithm.
    */
  val KeyName = "name"
}

/**
  * A specialized ''PlaylistReorderer'' trait which implements the ''name''
  * property by looking up a key from a resource bundle.
  *
  * This implementation assumes that the name of this objects is localized and
  * defined in a resource bundle. The base name of this resource bundle must be
  * provided by a concrete implementation. Then this bundle is loaded, and the
  * name is looked up using a reserved key.
  *
  * This trait can be extended by concrete ''PlaylistReorderer''
  * implementations which use this mechanism to determine the name of the
  * reorder algorithm.
  */
trait LocalizedPlaylistReorderer extends PlaylistReorderer {

  import LocalizedPlaylistReorderer._

  /**
    * The base name of the resource bundle. This bundle is loaded to determine
    * the name of the represented reorder algorithm.
    */
  val resourceBundleBaseName: String

  override lazy val name = loadNameFromResourceBundle()

  /**
    * Obtains the localized name of this object from the associated resource
    * bundle.
    *
    * @return the name of this object
    */
  private def loadNameFromResourceBundle(): String = {
    val bundle = ResourceBundle.getBundle(resourceBundleBaseName, Locale.getDefault, getClass
      .getClassLoader)
    bundle getString KeyName
  }
}
