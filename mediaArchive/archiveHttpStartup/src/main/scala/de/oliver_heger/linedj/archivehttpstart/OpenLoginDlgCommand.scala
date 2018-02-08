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

package de.oliver_heger.linedj.archivehttpstart

import java.util.concurrent.atomic.AtomicReference

import net.sf.jguiraffe.gui.app.{ApplicationBuilderData, OpenWindowCommand}
import net.sf.jguiraffe.locators.Locator

object OpenLoginDlgCommand {
  /**
    * The name of the Jelly property with the name of the current realm. This
    * property is set in the context of the dialog script; so it can be
    * evaluated by beans. The controller of the login dialog needs this
    * property to determine which realm is affected by the operation.
    */
  val PropertyRealmName = "realmName"
}

/**
  * A command for opening the login dialog for a specific realm.
  *
  * This command is invoked by the login action which is available if a realm
  * is selected in the startup application's main window. The name of the realm
  * affected can be obtained from an atomic reference that is passed to the
  * command's constructor.
  *
  * @param locator         the locator to the Jelly script to be executed
  * @param refCurrentRealm the reference containing the current realm name
  */
class OpenLoginDlgCommand(locator: Locator, refCurrentRealm: AtomicReference[String])
  extends OpenWindowCommand(locator) {

  import OpenLoginDlgCommand._

  override def prepareBuilderData(builderData: ApplicationBuilderData): Unit = {
    super.prepareBuilderData(builderData)
    builderData.addProperty(PropertyRealmName, refCurrentRealm.get())
  }
}
