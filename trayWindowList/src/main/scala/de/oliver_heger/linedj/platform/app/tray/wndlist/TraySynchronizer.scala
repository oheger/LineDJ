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

package de.oliver_heger.linedj.platform.app.tray.wndlist

import javax.swing.SwingUtilities

/**
  * Internally used helper class which manages threading when accessing the
  * system tray.
  *
  * Manipulations on the tray icon have to be performed in the AWT event
  * dispatch thread. This class is responsible for scheduling operations on
  * this thread.
  */
private class TraySynchronizer {
  /**
    * Runs the specified action on the AWT event dispatch thread.
    *
    * @param f the action to be executed
    */
  def schedule(f: => Unit): Unit = {
    SwingUtilities.invokeLater(new Runnable {
      override def run(): Unit = f
    })
  }
}
