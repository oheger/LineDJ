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

package de.oliver_heger.linedj.archivehttpstart

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.archivehttpstart.HttpArchiveStates.HttpArchiveState
import de.oliver_heger.linedj.platform.bus.UIBus
import net.sf.jguiraffe.gui.builder.event.{FormActionEvent, FormActionListener}
import net.sf.jguiraffe.gui.builder.window.{WindowEvent, WindowListener}
import net.sf.jguiraffe.gui.forms.ComponentHandler

/**
  * A controller class for the login form into an HTTP archive.
  *
  * When accessing the data from an HTTP-based media archive typically user
  * credentials are required. The startup implementation for an HTTP archive
  * therefore is a graphical application with a login form. This class
  * represents the controller of this login form.
  *
  * The form has input fields for the user name and a password. There are
  * buttons for logging in using the entered credentials and for logging out
  * (this will shutdown the connection to the HTTP archive). The controller
  * does not handle operations on the archive itself, but delegates to the
  * application class. Communication with the application is done via the
  * UI message bus.
  *
  * @param bus               the UI message bus
  * @param txtUser           the text component for the user name
  * @param txtPassword       the text component for the password
  * @param btnLogin          the component for the login button
  * @param btnLogout         the component for the logout button
  * @param statusLineHandler the object for managing the status line
  */
class HttpArchiveLoginController(bus: UIBus, txtUser: ComponentHandler[String],
                                 txtPassword: ComponentHandler[String],
                                 btnLogin: ComponentHandler[_],
                                 btnLogout: ComponentHandler[_],
                                 statusLineHandler: ArchiveStatusHelper)
  extends WindowListener with FormActionListener {
  /** The registration ID for the message bus receiver. */
  private var busRegistrationID: Int = _

  override def windowDeactivated(event: WindowEvent): Unit = {}

  override def windowIconified(event: WindowEvent): Unit = {}

  override def windowActivated(event: WindowEvent): Unit = {}

  override def windowClosing(event: WindowEvent): Unit = {}

  override def windowDeiconified(event: WindowEvent): Unit = {}

  /**
    * Notifies this controller that the associated window was opened. Here
    * some initialization code is located.
    *
    * @param event the window event
    */
  override def windowOpened(event: WindowEvent): Unit = {
    busRegistrationID = bus registerListener messageBusReceive
    btnLogout setEnabled false
    bus publish HttpArchiveStateRequest  // request initial state
  }

  /**
    * Notifies this controller that the associated window has been closed.
    * Here some cleanup is performed.
    *
    * @param event the window event
    */
  override def windowClosed(event: WindowEvent): Unit = {
    bus removeListener busRegistrationID
  }

  /**
    * Handles action events sent by the buttons in the form (login or logout).
    * This causes a corresponding ''LoginStateChanged'' message to be
    * published on the message bus.
    *
    * @param e the action event
    */
  override def actionPerformed(e: FormActionEvent): Unit = {
    e.getHandler match {
      case `btnLogin` =>
        handleLogin()

      case `btnLogout` =>
        handleLogout()
    }
  }

  /**
    * Handles a login operation. This is invoked when the login button was
    * clicked.
    */
  private def handleLogin(): Unit = {
    val credentials = UserCredentials(txtUser.getData, txtPassword.getData)
    bus publish LoginStateChanged(null, Some(credentials))
    btnLogout setEnabled true
    btnLogin setEnabled false
    txtPassword setData "" // reset password
  }

  /**
    * Handles a logout operation. This is invoked when the logout button was
    * clicked.
    */
  private def handleLogout(): Unit = {
    bus publish LoginStateChanged(null, None)
    btnLogin setEnabled true
    btnLogout setEnabled false
  }

  /**
    * The handling function for messages received via the message bus.
    * This controller handles notifications of state changes of the monitored
    * HTTP archive.
    *
    * @return the message handling fucntion
    */
  private def messageBusReceive: Receive = {
    case state: HttpArchiveState =>
      statusLineHandler updateStatusLine state
  }
}
