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

package de.oliver_heger.linedj.platform.mediaifc.remote

import de.oliver_heger.linedj.platform.app.ClientApplication
import net.sf.jguiraffe.gui.app.{Application, ApplicationClient}
import net.sf.jguiraffe.gui.builder.window.ctrl.{FormControllerFormEvent, FormControllerFormListener}
import org.apache.commons.configuration.Configuration

/**
  * A bean class for editing the configuration of the remote media interface.
  *
  * This bean defines properties for the host and the port under which the
  * media archive can be reached remotely. The properties are actually backed
  * by a ''Configuration'' object. So if this bean is passed to a form
  * controller, the data is read from and written to the underlying
  * configuration object. This makes it possible to store configuration for the
  * media archive in the configuration of the hosting application.
  */
class RemoteMediaIfcConfigBean extends ApplicationClient with FormControllerFormListener {
  /** The underlying configuration for this bean. */
  private var configuration: Configuration = _

  /** The application. */
  private var application: ClientApplication = _

  /**
    * Returns the host on which the media archive is running.
    *
    * @return the host of the media archive
    */
  def getHost: String = readHost(configuration)

  /**
    * Sets the host on which the media archive is running.
    *
    * @param host the host address
    */
  def setHost(host: String): Unit = {
    configuration.setProperty(PropMediaArchiveHost, host)
  }

  /**
    * Returns the port under which the media archive can be reached.
    *
    * @return the port of the media archive
    */
  def getPort: Int = readPort(configuration)

  /**
    * Sets the port under which the media archive can be reached.
    *
    * @param port the port
    */
  def setPort(port: Int): Unit = {
    configuration.setProperty(PropMediaArchivePort, port)
  }

  /**
    * Initializes this bean with the current application. This method is called
    * by the DI framework.
    *
    * @param app the current application
    */
  override def setApplication(app: Application): Unit = {
    application = app.asInstanceOf[ClientApplication]
    configuration = application.clientApplicationContext.managementConfiguration
  }

  /**
    * Notifies this object that the associated form has been closed. If the
    * form has been committed, the updated configuration now has to be applied
    * to the media interface.
    *
    * @param event the form event
    */
  override def formClosed(event: FormControllerFormEvent): Unit = {
    if (event.getType == FormControllerFormEvent.Type.FORM_COMMITTED) {
      val facade = application.clientApplicationContext.mediaFacade
      facade initConfiguration configuration
    }
  }
}
