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

package de.oliver_heger.linedj.platform.mediaifc.config

import net.sf.jguiraffe.locators.Locator

object MediaIfcConfigData {
  /**
    * Constant for the name of the class loader for loading classes from the
    * config bundle. When executing the Jelly configuration script referenced
    * by a ''MediaIfcConfigData'' object, the class loader to use for internal
    * classes is available from the ''ClassLoaderProvider'' under this name.
    */
  val ConfigClassLoaderName = "configCL"
}

/**
  * A trait providing information about the configuration of an interface to
  * the media archive.
  *
  * The way how the media archive is accessed depends on the implementation
  * deployed on the LineDJ platform. Some implementations, but not all, require
  * additional configuration data in order to access the media archive, for
  * instance the remote host the archive application is running on.
  * Applications making use of the media archive should allow users to define
  * this configuration; but as they do not know if and what configuration
  * settings to query, this is not trivial. Therefore, an abstract mechanism
  * has been introduced:
  *
  * An implementation of the media archive interface may register a service
  * of this type in the OSGi registry. This service can be picked up by the
  * application. The service provides information about a Jelly script to be
  * executed to display the configuration dialog. It is up to this script to
  * query the user for the required configuration data, to update the
  * application's configuration, and to apply changed settings directly.
  * That way, an application can offer a menu item for the configuration of
  * the media archive, even if it does not know details about this
  * configuration. (There are further helper classes in this package that
  * support the integration of such a menu item into an application.)
  */
trait MediaIfcConfigData {
  /**
    * Returns a ''Locator'' for a Jelly configuration script to be executed in
    * order to edit the configuration of the interface to the media archive.
    *
    * @return a ''Locator'' to a Jelly configuration script
    */
  def configScriptLocator: Locator

  /**
    * Returns a ''ClassLoader'' for loading classes referenced by the Jelly
    * configuration script. As these classes are not defined by the bundle of
    * the current application, they have to be referenced with a full class
    * loader reference. When the script is executed the class loader is
    * registered at the ''ClassLoaderProvider'' under a special name.
    *
    * @return the class loader for resolving classes for the config script
    */
  def configClassLoader: ClassLoader
}
