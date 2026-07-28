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

import de.oliver_heger.linedj.apps.archive.login.OpenCredentialDialogCommand.PropertyCredential
import net.sf.jguiraffe.gui.app.{ApplicationBuilderData, OpenWindowCommand}
import net.sf.jguiraffe.locators.Locator

import java.util.concurrent.atomic.AtomicReference

object OpenCredentialDialogCommand:
  /**
    * Constant for the name of the property with the name of the current
    * credential in the Jelly context.
    */
  final val PropertyCredential = "currentCredential"
end OpenCredentialDialogCommand

/**
  * A class representing the command to open a dialog for entering the value of
  * a credential. This dialog is opened from the main window controller for the
  * currently selected credential when the user triggers the corresponding
  * action. The command obtains the name of the affected credential from an
  * atomic reference that is updated by the main controller on table selection
  * changes. The controller of the credential dialog expects this name to be
  * contained in the Jelly context.
  *
  * @param locator              the [[Locator]] to the dialog to open
  * @param refCurrentCredential the reference containing the current credential
  */
class OpenCredentialDialogCommand(locator: Locator, refCurrentCredential: AtomicReference[String])
  extends OpenWindowCommand(locator):
  override def prepareBuilderData(builderData: ApplicationBuilderData): Unit =
    super.prepareBuilderData(builderData)
    builderData.addProperty(PropertyCredential, refCurrentCredential.get())
