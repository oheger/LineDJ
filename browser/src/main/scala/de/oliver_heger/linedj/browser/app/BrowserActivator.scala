/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.browser.app

import net.sf.jguiraffe.gui.app.{Application, ApplicationException}
import org.apache.commons.logging.LogFactory
import org.osgi.framework.{BundleActivator, BundleContext, BundleException}

/**
  * A bundle activator for starting the browser application from an OSGi
  * container.
  */
class BrowserActivator extends BundleActivator {
  /** The logger. */
  private val log = LogFactory.getLog(getClass)

  override def start(bundleContext: BundleContext): Unit = {
    log.info("Starting Browser application bundle.")
    val exitHandler = createExitHandler(bundleContext)

    new Thread() {
      override def run(): Unit = {
        val app = new BrowserApp
        app setExitHandler exitHandler
        try {
          Application.startup(app, Array.empty)
        } catch {
          case aex: ApplicationException =>
            log.error("Could not start application!", aex)
        }
      }
    }.start()
  }

  override def stop(bundleContext: BundleContext): Unit = {
    log.info("Stopping Browser application bundle.")
  }

  /**
    * Creates the exit handler for stopping the whole OSGi container when the
    * main application shuts down.
    * @param bundleContext the bundle context
    * @return the exit handler
    */
  private def createExitHandler(bundleContext: BundleContext): Runnable =
    new Runnable {
      override def run(): Unit = {
        val sysBundle = bundleContext getBundle 0
        try {
          sysBundle.stop()
        } catch {
          case e: BundleException =>
            log.error("Could not stop OSGi container!", e)
        }
      }
    }
}
