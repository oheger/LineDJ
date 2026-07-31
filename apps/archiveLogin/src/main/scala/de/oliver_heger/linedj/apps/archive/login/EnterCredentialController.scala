/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.apps.archive.login

import com.github.cloudfiles.core.http.Secret
import de.oliver_heger.linedj.platform.bus.UIBus
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler
import net.sf.jguiraffe.gui.builder.event.{FormActionEvent, FormActionListener}
import net.sf.jguiraffe.gui.builder.window.{Window, WindowEvent, WindowListener, WindowUtils}
import net.sf.jguiraffe.gui.forms.ComponentHandler
import net.sf.jguiraffe.resources.Message

import scala.compiletime.uninitialized

object EnterCredentialController:
  /** Key of the resource with the prompt to enter a specific credential. */
  private val PromptResource = "lab_credential_prompt"
end EnterCredentialController

/**
  * A controller class to manage a dialog window that allows entering the
  * value of a credential.
  *
  * The window consists of a prompt that is populated dynamically based on the
  * name of the current credential, a text field for the value of the
  * credential, and a button bar with an Okay and cancel buttons. When the user
  * presses the Okay button, the controller sends a ''CredentialEntered''
  * message on the UI bus with the data about the credential. This is processed
  * by the main controller, which then passes the credential to the login
  * service.
  *
  * @param applicationCtx the application context
  * @param bus            the UI message bus
  * @param txtCredential  the text field handler for the credential value
  * @param btnOk          the Okay button handler
  * @param btnCancel      the Cancel button handler
  * @param txtPrompt      the handler for the prompt text
  * @param credentialName the name of the current credential
  */
class EnterCredentialController(applicationCtx: ApplicationContext,
                                bus: UIBus,
                                txtCredential: ComponentHandler[String],
                                btnOk: ComponentHandler[?],
                                btnCancel: ComponentHandler[?],
                                txtPrompt: StaticTextHandler,
                                credentialName: String)
  extends WindowListener with FormActionListener:

  import EnterCredentialController.*

  /** Stores the managed window. */
  private var window: Window = uninitialized

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
  override def windowOpened(event: WindowEvent): Unit =
    window = WindowUtils.windowFromEventEx(event)
    txtPrompt.setText(applicationCtx.getResourceText(new Message(null, PromptResource, credentialName)))

  /**
    * Handles action events sent by the buttons in the form (OK or cancel).
    * In case of an OK click, a message is published on the message bus that
    * contains the credential entered by the user.
    *
    * @param e the action event
    */
  override def actionPerformed(e: FormActionEvent): Unit =
    e.getHandler match
      case `btnOk` =>
        handleLogin()

      case `btnCancel` =>
        handleCancel()

  /**
    * Handles a login operation. This is invoked when the OK button was
    * clicked. The entered credential value is published on the message bus;
    * then the dialog is closed.
    */
  private def handleLogin(): Unit =
    bus.publish(LoginController.CredentialEntered(credentialName, Secret(txtCredential.getData)))
    closeDialog()

  /**
    * Handles a cancel operation. This is invoked when the cancel button was
    * clicked. The dialog is just closed.
    */
  private def handleCancel(): Unit =
    closeDialog()

  /**
    * Closes this dialog window.
    */
  private def closeDialog(): Unit =
    window.close(false)
