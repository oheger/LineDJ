/*
 * Copyright 2015-2018 The Developers Team.
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

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.app.{ApplicationManager, ClientManagementApplication}
import de.oliver_heger.linedj.platform.comm.MessageBusListener
import net.sf.jguiraffe.gui.app.CommandActionTask

/**
  * An action task class for opening a dialog window with the configuration for
  * the media archive.
  *
  * This task class can be assigned to an action that displays a configuration
  * dialog specific to the interface to the media archive from a LineDJ
  * application. Here the problem is that the application has no built-in
  * knowledge about the configuration dialog to open (or whether such a dialog
  * is supported at all). This is decided dynamically at runtime depending on
  * the availability of a [[MediaIfcConfigData]] service.
  *
  * This action task class simplifies the integration of such a dialog into an
  * application. The idea is as follows: The task can be defined in the
  * application's builder script and assigned to an action. It can also be
  * passed a [[MediaIfcConfigStateHandler]] that receives notifications about
  * the current support for configuration options.
  *
  * The task class listens on the UI message bus for events related to the
  * availability state of a [[MediaIfcConfigData]] service. If such a service
  * is present, the associated state handler is notified, and a command object is
  * created which correctly opens the dialog window. The state handler can
  * update the UI according to the current state, so that options are displayed
  * only if configuration is supported.
  *
  * So, all an application has to do is to declare an instance of this class
  * and register it at the message bus. The instance takes then care whether a
  * configuration dialog is supported and how it can be displayed.
  *
  * @param stateHandler a handler to be notified whether a configuration dialog
  *                     is supported
  */
class OpenMediaIfcConfigTask(val stateHandler: MediaIfcConfigStateHandler)
  extends CommandActionTask with MessageBusListener {

  /**
    * Creates a new instance of ''OpenMediaIfcConfigTask'' that uses a dummy
    * state handler.
    */
  def this() = this(DummyMediaIfcConfigStateHandler)

  override def receive: Receive = {
    case ApplicationManager.ApplicationRegistered(app) =>
      initWithConfigData(app.clientApplicationContext.mediaIfcConfig)

    case ClientManagementApplication.MediaIfcConfigUpdated(optConfig) =>
      initWithConfigData(optConfig)
  }

  /**
    * Initializes the properties of this task for the specified configuration
    * data.
    *
    * @param config the optional configuration data
    */
  private def initWithConfigData(config: Option[MediaIfcConfigData]): Unit =
  config match {
    case None =>
      stateHandler updateState false

    case Some(configData) =>
      stateHandler updateState true
      setCommand(createCommand(configData))
  }

  /**
    * Creates the command for opening the configuration dialog.
    *
    * @param configData the object with data about the configuration
    * @return the command
    */
  private def createCommand(configData: MediaIfcConfigData): OpenMediaIfcConfigCommand = {
    val command = new OpenMediaIfcConfigCommand(configData)
    command setApplication getApplication
    command
  }
}

/**
  * A dummy ''MediaIfcConfigStateHandler'' implementation. This object is used
  * if no ''MediaIfcConfigStateHandler'' is passed to an
  * ''OpenMediaIfcConfigTask''. It has an empty ''updateState()'' method.
  */
private object DummyMediaIfcConfigStateHandler extends MediaIfcConfigStateHandler {
  override def updateState(configAvailable: Boolean): Unit = {}
}
