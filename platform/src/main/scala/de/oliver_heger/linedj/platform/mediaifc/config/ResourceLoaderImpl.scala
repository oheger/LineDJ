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

import java.util.Locale

import net.sf.jguiraffe.resources.{ResourceGroup, ResourceLoader}

/**
  * Internally used implementation of the ''ResourceLoader'' interface.
  *
  * Note: This class is used only temporarily until JGUIraffe exposes its
  * resource manager classes.
  *
  * @param classLoader the class loader for resolving resources
  */
private class ResourceLoaderImpl(classLoader: ClassLoader) extends ResourceLoader {
  override def loadGroup(locale: Locale, name: scala.Any): ResourceGroup =
    ResourceGroupImpl(String.valueOf(name), locale, classLoader)
}
