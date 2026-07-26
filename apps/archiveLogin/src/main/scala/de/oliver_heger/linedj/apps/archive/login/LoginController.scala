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

import de.oliver_heger.linedj.platform.archiveclient.{ArchiveService, ArchiveStateMonitor, LoginService}
import de.oliver_heger.linedj.platform.comm.{MessageBus, MessageBusListener}
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import net.sf.jguiraffe.gui.builder.event.{FormChangeEvent, FormChangeListener}
import net.sf.jguiraffe.gui.builder.window.{WindowEvent, WindowListener}
import org.apache.pekko.actor.Actor.Receive

import java.util.concurrent.atomic.AtomicReference
import scala.beans.BeanProperty
import scala.compiletime.uninitialized

object LoginController:
  /** The name of the action that allows entering a credential. */
  private val ActionEnterCredential = "actionEnterCredential"

  /**
    * Data class that represents an element in a table model.
    *
    * @param name      the name to be displayed
    * @param iconState the state icon for this element
    */
  private[login] case class TableElement(@BeanProperty name: String,
                                         @BeanProperty iconState: AnyRef)

  /**
    * An internally used message class that wraps a changed archive login
    * state. On receiving a change notification from the login service, the
    * controller publishes a message of this class on the message bus that it
    * can then process in the event dispatch thread.
    *
    * @param state the new archive login state
    */
  private[login] case class LoginStateChanged(state: LoginService.ArchiveLoginState)

  /**
    * Retrieves the [[TableElement]] from the model of a table handler at a
    * specific index.
    *
    * @param tableHandler the table handler
    * @param index        the index
    * @return the element at this index
    */
  private def elementAt(tableHandler: TableHandler, index: Int): TableElement =
    tableHandler.getModel.get(index).asInstanceOf[TableElement]
end LoginController

/**
  * The controller class for the main window of the Archive Login application.
  *
  * The window displays two tables for the current cloud archive state and the
  * pending credentials. This controller manages the table models. It is
  * registered as a change listener at the [[LoginService]] and thus receives
  * notifications with the recent archive login state. This state is then fed
  * into the table models. The class also tracks the current table selections,
  * so that actions can be enabled or disabled accordingly.
  *
  * @param messageBus           the message bus
  * @param actionStore          the action manager
  * @param loginService         the login service
  * @param archiveService       the archive service
  * @param tabArchives          the handler for the table of archives
  * @param tabCredentials       the handler for the table of credentials
  * @param statusHelper         the helper object for archive states
  * @param refCurrentCredential holds the selected credential
  */
class LoginController(messageBus: MessageBus,
                      actionStore: ActionStore,
                      loginService: LoginService,
                      archiveService: ArchiveService,
                      tabArchives: TableHandler,
                      tabCredentials: TableHandler,
                      statusHelper: ArchiveStatusHelper,
                      refCurrentCredential: AtomicReference[String])
  extends WindowListener, FormChangeListener, MessageBusListener,
    ArchiveStateMonitor.ArchiveChangeListener[LoginService.ArchiveLoginState]:

  import LoginController.*

  /** Stores the latest login state received via the message bus. */
  private var currentLoginState: LoginService.ArchiveLoginState = uninitialized

  override def windowActivated(event: WindowEvent): Unit = {}

  override def windowClosing(event: WindowEvent): Unit = {}

  override def windowClosed(event: WindowEvent): Unit =
    loginService.removeChangeListener(this)

  override def windowDeactivated(event: WindowEvent): Unit = {}

  override def windowDeiconified(event: WindowEvent): Unit = {}

  override def windowIconified(event: WindowEvent): Unit = {}

  override def windowOpened(event: WindowEvent): Unit =
    loginService.addChangeListener(this)
    enableEnterCredentialAction(enabled = false)

  /**
    * @inheritdoc This implementation handles changes in the selected index of
    *             the table handlers.
    */
  override def elementChanged(e: FormChangeEvent): Unit =
    e.getHandler match
      case `tabArchives` =>
        val index = tabArchives.getSelectedIndex
        if index >= 0 then
          val data = elementAt(tabArchives, index)
          statusHelper.updateStatusLine(ArchiveStatusHelper.stateFor(data.name, currentLoginState))
        else
          statusHelper.clearStatusLine()

      case `tabCredentials` =>
        val index = tabCredentials.getSelectedIndex
        if index >= 0 then
          enableEnterCredentialAction(enabled = true)
          refCurrentCredential.set(elementAt(tabCredentials, index).name)
        else
          enableEnterCredentialAction(enabled = false)
          refCurrentCredential.set(null)

  override def receive: Receive =
    case LoginStateChanged(loginState) =>
      val allArchives = loginState.loadedArchives ++ loginState.waitingArchives ++ loginState.failedArchives.keySet
      val archiveData = allArchives.map: name =>
        val state = ArchiveStatusHelper.stateFor(name, loginState)
        TableElement(name, statusHelper.iconForState(state))
      populateTable(tabArchives, archiveData)

      val credentialsData = (loginState.archiveCredentials ++ loginState.fileCredentials).map: cred =>
        TableElement(cred, statusHelper.iconWaiting)
      populateTable(tabCredentials, credentialsData)

      currentLoginState = loginState
      archiveService.expectChanges()

  /**
    * @inheritdoc This implementation publishes a message with the updated
    *             state on the message bus, so that it can process this message
    *             in the event dispatch thread.
    */
  override def archiveStateChanged(state: LoginService.ArchiveLoginState): Unit =
    messageBus.publish(LoginStateChanged(state))

  /**
    * Writes the specified data into the table model of the given handler. This
    * function is called when a change notification about the login state is
    * received. It also sorts the data correctly.
    *
    * @param handler the table handler
    * @param data    the data for this table
    */
  private def populateTable(handler: TableHandler, data: Iterable[TableElement]): Unit =
    val sortedData = data.toList.sortWith((d1, d2) => d1.name.compareToIgnoreCase(d2.name) < 0)
    handler.getModel.clear()
    sortedData.foreach(handler.getModel.add)
    handler.setSelectedIndex(-1)
    handler.tableDataChanged()

  /**
    * Sets the status of the action to enter a credential.
    *
    * @param enabled the enabled state of the action
    */
  private def enableEnterCredentialAction(enabled: Boolean): Unit =
    actionStore.getAction(ActionEnterCredential).setEnabled(enabled)
