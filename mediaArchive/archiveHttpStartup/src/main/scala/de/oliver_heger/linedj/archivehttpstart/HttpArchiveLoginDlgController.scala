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

import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.platform.bus.UIBus
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler
import net.sf.jguiraffe.gui.builder.event.{FormActionEvent, FormActionListener}
import net.sf.jguiraffe.gui.builder.window.{Window, WindowEvent, WindowListener, WindowUtils}
import net.sf.jguiraffe.gui.forms.ComponentHandler

/**
  * A controller class for the login form into an HTTP archive.
  *
  * When accessing the data from an HTTP-based media archive typically user
  * credentials are required. The startup implementation for an HTTP archive
  * supports multiple realms associated with different HTTP archives. (One
  * realm can store the login credentials for multiple archives.) This class
  * represents the controller of a form that gathers the login credentials for
  * a specific realm.
  *
  * The form has input fields for the user name and a password. There are
  * buttons for logging in using the entered credentials and to cancel the
  * form (this will close the window). The controller does not handle
  * operations on the archive itself, but delegates to the application class.
  * Communication with the application is done via the UI message bus.
  *
  * @param bus         the UI message bus
  * @param txtUser     the text component for the user name
  * @param txtPassword the text component for the password
  * @param btnLogin    the component for the login button
  * @param btnCancel   the component for the cancel button
  * @param txtRealm    the component handler for the realm text field
  * @param realmName   the name of the realm to log in
  */
class HttpArchiveLoginDlgController(bus: UIBus, txtUser: ComponentHandler[String],
                                    txtPassword: ComponentHandler[String],
                                    btnLogin: ComponentHandler[_],
                                    btnCancel: ComponentHandler[_],
                                    txtRealm: StaticTextHandler,
                                    realmName: String)
  extends WindowListener with FormActionListener {
  /** Stores the managed window. */
  private var window: Window = _

  override def windowDeactivated(event: WindowEvent): Unit = {}

  override def windowIconified(event: WindowEvent): Unit = {}

  override def windowActivated(event: WindowEvent): Unit = {}

  override def windowClosing(event: WindowEvent): Unit = {}

  override def windowDeiconified(event: WindowEvent): Unit = {}

  override def windowClosed(event: WindowEvent): Unit = {}

  /**
    * Notifies this controller that the associated window was opened. Here
    * some initialization code is located.
    *
    * @param event the window event
    */
  override def windowOpened(event: WindowEvent): Unit = {
    window = WindowUtils.windowFromEventEx(event)
    txtRealm setText realmName
  }

  /**
    * Handles action events sent by the buttons in the form (login or cancel).
    * In case of a login, a corresponding ''LoginStateChanged'' message is
    * published on the message bus.
    *
    * @param e the action event
    */
  override def actionPerformed(e: FormActionEvent): Unit = {
    e.getHandler match {
      case `btnLogin` =>
        handleLogin()

      case `btnCancel` =>
        handleCancel()
    }
  }

  /**
    * Handles a login operation. This is invoked when the login button was
    * clicked.
    */
  private def handleLogin(): Unit = {
    val credentials = UserCredentials(txtUser.getData, txtPassword.getData)
    bus publish LoginStateChanged(realmName, Some(credentials))
    closeDialog()
  }

  /**
    * Handles a cancel operation. This is invoked when the logout button was
    * clicked. The dialog is just closed.
    */
  private def handleCancel(): Unit = {
    closeDialog()
  }

  /**
    * Closes this dialog window.
    */
  private def closeDialog(): Unit = {
    window.close(false)
  }
}
