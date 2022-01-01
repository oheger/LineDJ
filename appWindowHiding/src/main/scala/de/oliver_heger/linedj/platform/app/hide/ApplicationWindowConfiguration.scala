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

package de.oliver_heger.linedj.platform.app.hide

import de.oliver_heger.linedj.platform.app.ClientApplication

/**
  * A trait used by the ''window hiding application manager'' to store the
  * state of visible or invisible application windows.
  *
  * Using this trait it is possible to persist the visibility state of windows,
  * so that after a restart a LineDJ deployment shows the same windows. The
  * trait defines one query method which is invoked by the application manager
  * when a new application is registered; it defines whether this application's
  * window should be displayed or not. There is an update method invoked by
  * the manager when the visibility state of an application window is changed.
  *
  * This trait also introduces the concept of ''main applications''. If an
  * application is marked as a main application, closing it causes the platform
  * to go down. This makes sense if there is one special application and a
  * number of auxiliary applications; the latter one can be made invisible, but
  * closing the main application means that the user wants to exit.
  */
trait ApplicationWindowConfiguration {
  /**
    * Returns the visibility state of the specified application. This method is
    * called when an application registers itself to set its initial state.
    *
    * @param app the application in question
    * @return a flag whether this application's window is visible
    */
  def isWindowVisible(app: ClientApplication): Boolean

  /**
    * Sets the visible state of the specified application. This method is
    * called when state of this window is modified (typically in reaction of a
    * user action).
    *
    * @param app     the application in question
    * @param visible the new visible state of this application
    */
  def setWindowVisible(app: ClientApplication, visible: Boolean): Unit

  /**
    * Returns a flag whether the specified application is a ''main
    * application''. In this case, closing the application will cause the
    * platform to shutdown.
    *
    * @param app the application in question
    * @return a flag whether this is a main application
    */
  def isMainApplication(app: ClientApplication): Boolean
}
