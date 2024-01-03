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

package de.oliver_heger.linedj.platform.app.hide.impl

import de.oliver_heger.linedj.platform.app.ClientApplication
import de.oliver_heger.linedj.platform.app.hide.ApplicationWindowConfiguration
import org.apache.commons.configuration.Configuration

object AppConfigWindowConfiguration:
  /**
    * Constant for the key prefix for the window management section in the
    * underlying configuration.
    */
  private val Section = "platform.windowManagement."

  /**
    * Constant for the key that determines whether this configuration contains
    * window management data.
    */
  private val KeyConfig = Section + "config"

  /**
    * Constant for the key prefix for the options defining the visibility of
    * single applications.
    */
  private val KeyApps = Section + "apps."

  /**
    * Constant for the key that defines the list of main applications.
    */
  private val KeyMainApps = Section + "main.name"

  /**
    * Calculates the configuration key that stores the visibility state of the
    * specified application.
    *
    * @param app the application
    * @return the visibility state key for this application
    */
  private def appVisibleKey(app: ClientApplication): String =
    KeyApps + app.appName

  /**
    * Checks whether the specified configuration contains window management
    * data. If so, returns a defined option with an
    * ''ApplicationWindowConfiguration'' instance. Otherwise, result is
    * ''None''.
    *
    * @param config the configuration to evaluate
    * @return an option with an ''ApplicationWindowConfiguration'' instance
    */
  def apply(config: Configuration): Option[ApplicationWindowConfiguration] =
    if config.getBoolean(KeyConfig, false) then
      Some(new AppConfigWindowConfiguration(config))
    else None

/**
  * An implementation of the ''ApplicationWindowConfiguration'' trait that is
  * based on the user configuration of an application.
  *
  * The idea behind this class is that one of the applications installed on the
  * LineDJ platform (e.g. the ''main application'' or a special management
  * application) stores information about visible or invisible applications in
  * its user configuration. When applications register at the application
  * manager on startup the manager can check whether their configuration
  * contains such data. If so, it uses the data stored in the configuration to
  * initialize the visibility of application windows, and updates it when there
  * are changes.
  *
  * In order to enable this mechanism, the configuration must have a boolean
  * property ''platform.windowManagement.config'' with the value '''true'''.
  * Data about specific applications is then stored under the key
  * ''platform.windowManagement.apps'': Here the application name is used as
  * key and its visibility state (true or false) as value. An application not
  * contained in this list is considered ''visible''. (This makes sure that
  * newly installed applications are shown.)
  *
  * Main applications are stored as a list structure under the key
  * ''platform.windowManagement.main.name''. Here the names of the
  * applications to be considered as main applications have to be specified.
  *
  * @param configuration the underlying configuration
  */
class AppConfigWindowConfiguration private(configuration: Configuration)
  extends ApplicationWindowConfiguration:

  import AppConfigWindowConfiguration._

  /**
    * Returns the visibility state of the specified application. This method is
    * called when an application registers itself to set its initial state.
    *
    * @param app the application in question
    * @return a flag whether this application's window is visible
    */
  override def isWindowVisible(app: ClientApplication): Boolean =
    configuration.getBoolean(appVisibleKey(app), true)

  /**
    * Sets the visible state of the specified application. This method is
    * called when state of this window is modified (typically in reaction of a
    * user action).
    *
    * @param app     the application in question
    * @param visible the new visible state of this application
    */
  override def setWindowVisible(app: ClientApplication, visible: Boolean): Unit =
    val key = appVisibleKey(app)
    if visible then configuration.clearProperty(key)
    else configuration.setProperty(key, visible)

  /**
    * Returns a flag whether the specified application is a ''main
    * application''. In this case, closing the application will cause the
    * platform to shutdown.
    *
    * @param app the application in question
    * @return a flag whether this is a main application
    */
  override def isMainApplication(app: ClientApplication): Boolean =
    configuration.getList(KeyMainApps) contains app.appName
