/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.archivehttpstart.app

import com.github.cloudfiles.core.http.Secret
import de.oliver_heger.linedj.archivehttp.config.UserCredentials
import de.oliver_heger.linedj.crypt.KeyGenerator
import de.oliver_heger.linedj.platform.bus.UIBus
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler
import net.sf.jguiraffe.gui.builder.event.{FormActionEvent, FormActionListener}
import net.sf.jguiraffe.gui.builder.window.{Window, WindowEvent, WindowListener, WindowUtils}
import net.sf.jguiraffe.gui.forms.ComponentHandler

/**
  * A base class for controllers of dialog windows related to properties of an
  * HTTP archive.
  *
  * This class offers basic functionality to manage a dialog with an OK and a
  * Cancel button and some input controls. The dialog contains a message with a
  * prompt referencing an archive; here a concrete name is inserted. The class
  * reacts on clicks on the buttons. While the cancel button simply closes the
  * dialog window, the OK button causes a message to be published on the UI
  * bus. A concrete subclass must generate this message based on the current
  * values in the input fields.
  *
  * @param bus       the UI message bus
  * @param btnOk     the component handler for the OK button
  * @param btnCancel the component handler for the cancel button
  * @param txtPrompt the component handler for the prompt text
  * @param name      the name to be added to the prompt text
  */
abstract class HttpArchiveDlgController(bus: UIBus,
                                        btnOk: ComponentHandler[_],
                                        btnCancel: ComponentHandler[_],
                                        txtPrompt: StaticTextHandler,
                                        name: String)
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
    txtPrompt setText name
  }

  /**
    * Handles action events sent by the buttons in the form (OK or cancel).
    * In case of an OK click, a message is published on the message bus that
    * is generated by the ''generateOkMessage()'' method.
    *
    * @param e the action event
    */
  override def actionPerformed(e: FormActionEvent): Unit = {
    e.getHandler match {
      case `btnOk` =>
        handleLogin()

      case `btnCancel` =>
        handleCancel()
    }
  }

  /**
    * Generates the message that is published on the message bus when the OK
    * button is clicked.
    *
    * @return the message indicating an OK click
    */
  protected def generateOkMessage(): Any

  /**
    * Handles a login operation. This is invoked when the login button was
    * clicked.
    */
  private def handleLogin(): Unit = {
    bus publish generateOkMessage()
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
  * @param realm       the realm to log in
  */
class HttpArchiveLoginDlgController(bus: UIBus, txtUser: ComponentHandler[String],
                                    txtPassword: ComponentHandler[String],
                                    btnLogin: ComponentHandler[_],
                                    btnCancel: ComponentHandler[_],
                                    txtRealm: StaticTextHandler,
                                    realm: ArchiveRealm)
  extends HttpArchiveDlgController(bus, btnLogin, btnCancel, txtRealm, realm.name) {

  /**
    * @inheritdoc This implementation does some more initializations on input
    *             components.
    */
  override def windowOpened(event: WindowEvent): Unit = {
    super.windowOpened(event)
    txtUser setEnabled realm.needsUserID
  }

  /**
    * @inheritdoc This implementation produces a ''LoginStateChanged'' message
    *             with the credentials entered in this dialog.
    */
  override protected def generateOkMessage(): Any = {
    val credentials = UserCredentials(txtUser.getData, Secret(txtPassword.getData))
    LoginStateChanged(realm.name, Some(credentials))
  }
}

/**
  * A controller class for the unlock dialog for HTTP archives.
  *
  * This dialog is used for encrypted archives. In this case, the user has to
  * enter the password to unlock the archive first. The file names and the
  * content is then decrypted using this password.
  *
  * The dialog has a field for the password. When the user enters the password
  * and presses the confirmation button a message is sent on the UI message bus
  * to pass this information to the application.
  *
  * @param bus          the UI message bus
  * @param txtPassword  the handler for the password field
  * @param btnOk        the component handler for the OK button
  * @param btnCancel    the component handler for the cancel button
  * @param txtPrompt    the component handler for the prompt text
  * @param archiveName  the name of the current HTTP archive
  * @param keyGenerator the object to generate the secret key
  */
class HttpArchiveUnlockDlgController(bus: UIBus,
                                     txtPassword: ComponentHandler[String],
                                     btnOk: ComponentHandler[_],
                                     btnCancel: ComponentHandler[_],
                                     txtPrompt: StaticTextHandler,
                                     archiveName: String,
                                     keyGenerator: KeyGenerator)
  extends HttpArchiveDlgController(bus, btnOk, btnCancel, txtPrompt, archiveName) {
  /**
    * @inheritdoc This implementation produces a ''LockStateChanged'' message
    *             with the crypt key generated from the entered password.
    */
  override protected def generateOkMessage(): Any = {
    val key = keyGenerator.generateKey(txtPassword.getData)
    LockStateChanged(archiveName, Some(key))
  }
}

/**
  * A controller class allowing to enter the "super password".
  *
  * The super password is used to encrypt a file containing credentials for the
  * archives managed by the HTTP archive startup application. By entering the
  * password, all archives whose credentials are stored in this file can be
  * opened and unlocked.
  *
  * The password can be entered for reading or writing the file. Based on this
  * mode, the controller class behaves slightly differently, e.g. different
  * messages are generated when the user confirms the dialog.
  *
  * @param bus               the UI message bus
  * @param txtPassword       the handler for the password field
  * @param btnOk             the component handler for the OK button
  * @param btnCancel         the component handler for the cancel button
  * @param txtPrompt         the component handler for the prompt text
  * @param superPasswordMode the mode (read or write the file)
  * @param labelRead         a label to use in read mode
  * @param labelWrite        a label to use in write mode
  */
class HttpArchiveSuperPasswordDlgController(bus: UIBus,
                                            txtPassword: ComponentHandler[String],
                                            btnOk: ComponentHandler[_],
                                            btnCancel: ComponentHandler[_],
                                            txtPrompt: StaticTextHandler,
                                            superPasswordMode: String,
                                            labelRead: String,
                                            labelWrite: String)
  extends HttpArchiveDlgController(bus, btnOk, btnCancel, txtPrompt,
    if (superPasswordMode == OpenDlgCommand.SuperPasswordModeRead) labelRead
    else labelWrite) {
  /**
    * @inheritdoc This implementation generates one of the
    *             ''SuperPasswordEntered'' messages, depending on the current
    *             super password mode.
    */
  override protected def generateOkMessage(): Any = {
    val password = txtPassword.getData
    superPasswordMode match {
      case OpenDlgCommand.SuperPasswordModeWrite =>
        SuperPasswordEnteredForWrite(password)
      case _ =>
        SuperPasswordEnteredForRead(txtPassword.getData)
    }
  }
}
