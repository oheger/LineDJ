/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.platform.mediaifc.config

import java.util
import java.util.{Locale, ResourceBundle}

import net.sf.jguiraffe.resources.ResourceGroup

private object ResourceGroupImpl {
  /**
    * Creates a new instance based on the given parameters.
    *
    * @param name        the name of the group
    * @param locale      the locale
    * @param classLoader the class loader
    * @return the new resource group
    * @throws java.util.MissingResourceException if the resource group cannot
    *                                            be resolved
    */
  def apply(name: String, locale: Locale, classLoader: ClassLoader): ResourceGroup = {
    val bundle = ResourceBundle.getBundle(name, locale, classLoader)
    new ResourceGroupImpl(locale, name, createKeys(bundle), bundle)
  }

  /**
    * Generates a set with all keys contained in the given bundle.
    *
    * @param bundle the bundle
    * @return the set with all keys
    */
  private def createKeys(bundle: ResourceBundle): util.Set[AnyRef] = {
    val keys = new util.HashSet[AnyRef]
    val en = bundle.getKeys
    while (en.hasMoreElements) {
      keys add en.nextElement()
    }
    keys
  }
}

/**
  * Internally used implementation of the ''ResourceGroup'' interface.
  *
  * Note: This class is used only temporarily until JGUIraffe exposes its
  * resource manager classes.
  *
  * @param getLocale the locale of this group
  * @param getName   the name of this group
  * @param bundle    the resource bundle
  */
private class ResourceGroupImpl(override val getLocale: Locale,
                                override val getName: AnyRef,
                                override val getKeys: util.Set[AnyRef],
                                bundle: ResourceBundle) extends ResourceGroup {
  override def getResource(key: scala.Any): AnyRef = {
    bundle.getObject(String.valueOf(key))
  }
}
