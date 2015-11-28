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

import akka.actor.ActorSystem
import akka.osgi.ActorSystemActivator
import net.sf.jguiraffe.gui.app.{Application, ApplicationException}
import org.apache.commons.logging.LogFactory
import org.osgi.framework.{BundleContext, BundleException}

/**
  * A bundle activator for starting the browser application from an OSGi
  * container.
  *
  * @param applicationFactory the factory for creating the application
  */
class BrowserActivator(private[app] val applicationFactory: ApplicationFactory) extends ActorSystemActivator {
  /** The logger. */
  private val log = LogFactory.getLog(getClass)

  /**
    * Creates a ''BrowserActivator'' with a default application factory.
    */
  def this() = this(new BrowserApplicationFactoryImpl)

  /**
    * @inheritdoc This implementation creates a new browser application and
    *             triggers its startup.
    */
  override def configure(context: BundleContext, system: ActorSystem): Unit = {
    log.info("Starting Browser application bundle.")
    val exitHandler = createExitHandler(context)

    new Thread() {
      override def run(): Unit = {
        val app = applicationFactory createApplication Some(system)
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

  /**
    * Creates the exit handler for stopping the whole OSGi container when the
    * main application shuts down.
    * @param bundleContext the bundle context
    * @return the exit handler
    */
  private def createExitHandler(bundleContext: BundleContext): Runnable =
    new Runnable {
      override def run(): Unit = {
        log.info("Application exit handler called.")
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

/**
  * A default ''ApplicationFactory'' implementation used by the activator if
  * no factory has been provided.
  */
private class BrowserApplicationFactoryImpl extends ApplicationFactory {
  override def createApplication(optActorSystem: Option[ActorSystem]): BrowserApp =
    new BrowserApp(new RemoteMessageBusFactory, optActorSystem)
}
