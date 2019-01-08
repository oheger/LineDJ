/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.platform.app

import de.oliver_heger.linedj.utils.SystemPropertyAccess
import net.sf.jguiraffe.gui.app.Application

object ApplicationStartup {
  /**
    * Constant for a system property that defines an ID for a LineDJ
    * application. If this property is set, its value is prepended to the
    * configuration name passed to the ''startApplication()'' method.
    */
  val PropApplicationID = "LineDJ_ApplicationID"
}

/**
  * Definition of a trait which supports starting up an application.
  *
  * When started in an OSGi environment application startup should normally
  * happen in a background thread so that the OSGi management thread is not
  * blocked. This, however, makes tests complicated. Therefore, this trait
  * is introduced which defines startup logic. By mixing in different
  * implementations, the startup behavior of an application can be
  * controlled.
  *
  * The main method defined by this trait expects the application to be
  * started and an application name which must be unique for the LineDJ
  * application in question. From this application name the names for the main
  * JGUIraffe configuration and the user configuration are derived: The main
  * configuration is named ''appName_config.xml'', the user configuration is
  * named ''.lineDJ-appName.xml''. It is possible to adapt the latter in the
  * following way:
  *
  * If a system property ''appName_config'' is defined, the value of this
  * property is directly used as name for the user configuration. Otherwise,
  * the trait evaluates the system property defined by the
  * [[ApplicationStartup#PropApplicationID]] property. If specified, this
  * prefix is added to the name of the user configuration.
  *
  * With these mechanisms LineDJ applications deployed into different
  * environments can be handled. Their configurations can either be shared or
  * isolated from each other. In order to pass the name of the user
  * configuration to the configuration library, a system property named
  * ''appName_user_config'' is set; this variable can then be referenced in the
  * configuration definition file.
  */
trait ApplicationStartup extends SystemPropertyAccess {

  import ApplicationStartup._

  /**
    * Starts the specified application using the provided configuration
    * resource. An implementation should trigger the start of the given
    * application. It can decide to do this in a blocking or non-blocking
    * way.
    *
    * @param app     the application to be started
    * @param appName a unique name for this application
    */
  def startApplication(app: Application, appName: String): Unit

  /**
    * Implements the logic for starting up an application. This method can be
    * invoked by derived classes to actually do the startup.
    *
    * @param app     the application to be started
    * @param appName the name of the application to be started
    */
  protected def doStartApplication(app: Application, appName: String): Unit = {
    app setConfigResourceName generateConfigName(appName)
    System.setProperty(appName + "_user_config", generateUserConfigName(appName))
    Application.startup(app, Array.empty)
  }

  /**
    * Generates the name of the main configuration from the passed in
    * application name.
    *
    * @param appName the application name
    * @return the name of the main configuration
    */
  private def generateConfigName(appName: String): String =
  s"${appName}_config.xml"

  /**
    * Generates the name of the user configuration based on the passed in
    * application name. This method checks whether a system property for the
    * passed in configuration name exists. If this is the case, its value is
    * returned. Otherwise, it is checked whether the system property for the
    * LineDJ application ID is set. If so, the ID is combined with the
    * passed in configuration name.
    *
    * @param appName the application name
    * @return the user configuration name to be used
    */
  private def generateUserConfigName(appName: String): String = {
    val nameFromProperty = getSystemProperty(appName + "_config")
    nameFromProperty.getOrElse(generateUserConfigNameWithAppID(appName))
  }

  /**
    * Generates the name of the user configuration using the default naming
    * pattern, appending an application ID if it is defined.
    *
    * @param appName the application name
    * @return the user configuration name to be used
    */
  private def generateUserConfigNameWithAppID(appName: String): String = {
    val prefix1 = ".lineDJ-" + getSystemProperty(PropApplicationID).getOrElse("")
    val prefix = if (prefix1 endsWith "-") prefix1 else prefix1 + "-"
    prefix + appName + ".xml"
  }
}
