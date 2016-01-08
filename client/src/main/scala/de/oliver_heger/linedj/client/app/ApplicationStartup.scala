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

package de.oliver_heger.linedj.client.app

import net.sf.jguiraffe.gui.app.Application

/**
  * Definition of a trait which supports starting up an application.
  *
  * When started in an OSGi environment application startup should normally
  * happen in a background thread so that the OSGi management thread is not
  * blocked. This, however, makes tests complicated. Therefore, this trait
  * is introduced which defines startup logic. By mixing in different
  * implementations, the startup behavior of an application can be
  * controlled.
  */
trait ApplicationStartup {
  /**
    * Starts the specified application using the provided configuration
    * resource. An implementation should trigger the start of the given
    * application. It can decide to do this in a blocking or non-blocking
    * way.
    * @param app the application to be started
    * @param configName the resource name of the configuration
    */
  def startApplication(app: Application, configName: String): Unit

  /**
    * Implements the logic for starting up an application. This method can be
    * invoked by derived classes to actually do the startup.
    * @param app the application to be started
    * @param configName the resource name of the configuration
    */
  protected def doStartApplication(app: Application, configName: String): Unit = {
    app setConfigResourceName configName
    Application.startup(app, Array.empty)
  }
}
