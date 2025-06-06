/*
 * Copyright 2015-2025 The Developers Team.
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

import net.sf.jguiraffe.gui.app.{ApplicationBuilderData, OpenWindowCommand}
import net.sf.jguiraffe.locators.Locator

import java.util.concurrent.atomic.AtomicReference

object OpenDlgCommand:
  /** The name of the Jelly property with the current realm. */
  final val PropertyRealm = "realm"

  /** The name of the Jelly property with the name of the current archive. */
  final val PropertyArchiveName = "archiveName"

  /**
    * The name of the Jelly property containing the mode for dealing with the
    * super password. This mode determines whether the file with credentials is
    * read or written.
    */
  final val PropertySuperPasswordMode = "superPasswordMode"

  /** Constant indicating that the super password file is to be read. */
  final val SuperPasswordModeRead = "readSuperPasswordFile"

  /** Constant indicating that the super password file is to be written. */
  final val SuperPasswordModeWrite = "writeSuperPasswordFile"

/**
  * A base class for commands that open dialogs for gathering state information
  * related to archives.
  *
  * The dialogs to be opened require a reference to the entity that is affected
  * by the current operation. This is provided as a property in the Jelly
  * context. The source of this property is an atomic reference that is updated
  * by the controller of the main window whenever the user changes the
  * selection in the UI.
  *
  * @param locator  the locator of the Jelly script to be executed
  * @param refName  the reference containing the name of the current element
  * @param property the name of the property to be added to the Jelly context
  */
abstract class OpenDlgCommand(locator: Locator, refName: AtomicReference[_], property: String)
  extends OpenWindowCommand(locator):
  override def prepareBuilderData(builderData: ApplicationBuilderData): Unit =
    super.prepareBuilderData(builderData)
    builderData.addProperty(property, refName.get())

/**
  * A command for opening the login dialog for a specific realm.
  *
  * This command is invoked by the login action which is available if a realm
  * is selected in the startup application's main window. The name of the realm
  * affected can be obtained from an atomic reference that is passed to the
  * command's constructor.
  *
  * @param locator         the locator to the Jelly script to be executed
  * @param refCurrentRealm the reference containing the current realm
  */
class OpenLoginDlgCommand(locator: Locator, refCurrentRealm: AtomicReference[ArchiveRealm])
  extends OpenDlgCommand(locator, refCurrentRealm, OpenDlgCommand.PropertyRealm)

/**
  * A command for opening the dialog to unlock a specific archive.
  *
  * This command is invoked by the ''unlock archive'' action which is enabled
  * when in the archive table an encrypted archive is selected which has not
  * yet been unlocked
  *
  * @param locator           the locator to the Jelly script to be executed
  * @param refCurrentArchive the reference containing the current archive name
  */
class OpenUnlockDlgCommand(locator: Locator, refCurrentArchive: AtomicReference[String])
  extends OpenDlgCommand(locator, refCurrentArchive, OpenDlgCommand.PropertyArchiveName)

/**
  * A command for opening the dialog to enter the super password.
  *
  * This command is invoked by the commands to read or write the file with
  * information about credentials available. Which action to trigger is
  * determined by the ''mode'' parameter.
  *
  * @param locator the locator of the Jelly script to be executed
  * @param mode    the mode (read or write the credentials file)
  */
class OpenSuperPasswordDlgCommand(locator: Locator, mode: String)
  extends OpenDlgCommand(locator, new AtomicReference(mode), OpenDlgCommand.PropertySuperPasswordMode)
