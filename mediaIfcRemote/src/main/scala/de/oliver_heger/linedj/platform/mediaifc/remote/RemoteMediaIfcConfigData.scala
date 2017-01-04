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

package de.oliver_heger.linedj.platform.mediaifc.remote

import de.oliver_heger.linedj.platform.mediaifc.config.MediaIfcConfigData
import net.sf.jguiraffe.locators.{ClassPathLocator, Locator}

/**
  * Implementation of ''MediaIfcConfigData'' for the remote media archive
  * interface.
  *
  * This class exposes properties to access the Jelly script with the specific
  * configuration dialog.
  */
class RemoteMediaIfcConfigData extends MediaIfcConfigData {
  /**
    * @inheritdoc This implementation returns the class loader of this class.
    */
  override val configClassLoader: ClassLoader = getClass.getClassLoader

  /**
    * @inheritdoc This implementation returns a locator to the specific Jelly
    *             script with the configuration dialog.
    */
  override val configScriptLocator: Locator =
  ClassPathLocator.getInstance("remoteconfig.jelly", configClassLoader)
}
