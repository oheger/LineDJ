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

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.archivehttpstart.HttpArchiveStates.{HttpArchiveState,
HttpArchiveStateInitializing, HttpArchiveStateNoUnionArchive}
import de.oliver_heger.linedj.platform.comm.{MessageBus, MessageBusListener}
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import net.sf.jguiraffe.gui.builder.event.{FormChangeEvent, FormChangeListener}
import net.sf.jguiraffe.gui.builder.window.{WindowEvent, WindowListener}

import scala.annotation.tailrec
import scala.beans.BeanProperty

object HttpArchiveOverviewController {
  /** Name of the login action. */
  private val ActionLogin = "actionLogin"

  /** Name of the logout action. */
  private val ActionLogout = "actionLogout"

  /** Name of the logout all action. */
  private val ActionLogoutAll = "actionLogoutAll"
}

/**
  * The controller class for the main window of the HTTP archive startup
  * application.
  *
  * The main window displays an overview over the HTTP archives managed by this
  * application and their current states. The user can see with a single glance
  * which archives are available and whether they are currently active or not.
  *
  * Another part of the UI shows information about the existing realms. The
  * user can enter credentials for a specific realm or log out from it.
  *
  * Status information is mainly managed by the
  * [[HttpArchiveStartupApplication]] class. Notifications about status updates
  * are propagated via the message bus.
  *
  * @param messageBus      the message bus
  * @param configManager   the object managing the archive configuration
  * @param actionStore     the action manager
  * @param tabArchives     the handler for the table of archives
  * @param tabRealms       the handler for the table of realms
  * @param statusHelper    the helper object for archive states
  * @param refCurrentRealm holds the name of the current realm
  */
class HttpArchiveOverviewController(messageBus: MessageBus, configManager: HttpArchiveConfigManager,
                                    actionStore: ActionStore, tabArchives: TableHandler,
                                    tabRealms: TableHandler, statusHelper: ArchiveStatusHelper,
                                    refCurrentRealm: AtomicReference[String])
  extends WindowListener with MessageBusListener with FormChangeListener {

  import HttpArchiveOverviewController._

  /**
    * Stores the realms for which credentials are available. Keys of the map
    * are the indices in the realms table. The values are the corresponding
    * realm names.
    */
  private var loggedInRealms = Map.empty[Int, String]

  /** An array with state information for the managed archives. */
  private var archiveStates: Array[HttpArchiveState] = _

  override def windowActivated(windowEvent: WindowEvent): Unit = {}

  override def windowClosing(windowEvent: WindowEvent): Unit = {}

  override def windowDeactivated(windowEvent: WindowEvent): Unit = {}

  override def windowIconified(windowEvent: WindowEvent): Unit = {}

  override def windowClosed(windowEvent: WindowEvent): Unit = {}

  override def windowDeiconified(windowEvent: WindowEvent): Unit = {}

  /**
    * Performs initialization when the window is opened. For instance, the
    * table elements are initialized from the data provided by the
    * configuration manager.
    */
  override def windowOpened(windowEvent: WindowEvent): Unit = {
    archiveStates = Array.fill(configManager.archives.size)(HttpArchiveStateNoUnionArchive)
    configManager.archives.keys foreach { n =>
      tabArchives.getModel.add(TableElement(n, statusHelper.iconInactive))
    }
    val realms = configManager.archives.values.map(_.realm).toSet.toSeq.sorted
    realms foreach { r =>
      tabRealms.getModel.add(TableElement(r, statusHelper.iconInactive))
    }

    tabArchives.tableDataChanged()
    tabRealms.tableDataChanged()

    enableAction(ActionLogin, enabled = false)
    enableAction(ActionLogout, enabled = false)
    enableAction(ActionLogoutAll, enabled = false)

    messageBus publish HttpArchiveStateRequest
  }

  /**
    * Returns the function for handling messages published on the message bus.
    *
    * @return the message handling function
    */
  override def receive: Receive = {
    case HttpArchiveStateChanged(name, state) =>
      val (elem, idx) = findArchiveIndex(name)
      if (idx >= 0) {
        archiveStates(idx) = state
        tabArchives.getModel.set(idx, elem.copy(icon = iconForState(state)))
        tabArchives.rowsUpdated(idx, idx)
        if (tabArchives.getSelectedIndex == idx) {
          statusHelper updateStatusLine state
        }
      }

    case LoginStateChanged(realm, optCred) =>
      val (elem, idx) = findRealIndex(realm)
      if (idx >= 0) {
        val icon = if (optCred.isDefined) statusHelper.iconActive
        else statusHelper.iconInactive
        loggedInRealms = if (optCred.isDefined) loggedInRealms + (idx -> realm)
        else loggedInRealms - idx
        tabRealms.getModel.set(idx, elem.copy(icon = icon))
        tabRealms.rowsUpdated(idx, idx)
        enableAction(ActionLogoutAll, enabled = loggedInRealms.nonEmpty)
        if (idx == tabRealms.getSelectedIndex) {
          enableAction(ActionLogout, enabled = loggedInRealms.contains(idx))
        }
      }
  }

  /**
    * Notifies this object about an element change. This method is called when
    * the selection in one of the managed tables has changed. This causes some
    * updates on the UI
    *
    * @param event the change event
    */
  override def elementChanged(event: FormChangeEvent): Unit = {
    event.getHandler match {
      case `tabArchives` =>
        val index = tabArchives.getSelectedIndex
        if (index >= 0) statusHelper.updateStatusLine(archiveStates(index))
        else statusHelper.clearStatusLine()

      case `tabRealms` =>
        val index = tabRealms.getSelectedIndex
        enableAction(ActionLogin, enabled = index != -1)
        enableAction(ActionLogout, enabled = loggedInRealms contains index)
        refCurrentRealm.set(loggedInRealms.getOrElse(index, currentRealmName(index)))
    }
  }

  /**
    * Handles the action to logout the currently selected realm. If a realm is
    * currently selected, a message is sent on the message bus indicating that
    * its login state changed to ''no credentials available''. This causes the
    * application to discard the credentials associated with this realm.
    */
  def logoutCurrentRealm(): Unit = {
    sendLogoutsForRealms(Option(refCurrentRealm.get()))
  }

  /**
    * Handles the action to logout all currently logged in realms. For each
    * realm in state ''logged in'' a message is published on the message bus
    * to discard the current credentials.
    */
  def logoutAllRealms(): Unit = {
    sendLogoutsForRealms(loggedInRealms.values)
  }

  /**
    * Sends logout messages for the specified realm names.
    *
    * @param realmNames a collection with realm names
    */
  private def sendLogoutsForRealms(realmNames: Iterable[String]): Unit = {
    realmNames.map(LoginStateChanged(_, None)) foreach messageBus.publish
  }

  /**
    * Sets the enabled state of the specified action.
    *
    * @param name    the action name
    * @param enabled the enabled state
    */
  private def enableAction(name: String, enabled: Boolean): Unit = {
    actionStore.getAction(name) setEnabled enabled
  }

  /**
    * Determines the index of an archive based on its name.
    *
    * @param name the name of the archive
    * @return a tuple with the element and the index of this archive (-1 if
    *         not found)
    */
  private def findArchiveIndex(name: String): (TableElement, Int) =
    findElementIndex(name, tabArchives.getModel, 0)

  /**
    * Determines the index of a realm based on its name.
    *
    * @param name the name of the realm
    * @return a tuple with the element representing the realm and its index;
    *         the index is -1 if the name could not be resolved
    */
  private def findRealIndex(name: String): (TableElement, Int) =
    findElementIndex(name, tabRealms.getModel, 0)

  /**
    * Helper method for finding the index of an element in a table model by
    * name.
    *
    * @param name  the name of the element
    * @param model the table model to be searched
    * @param idx   the current index
    * @return a tuple with the found element and its index or -1
    */
  @tailrec private def findElementIndex(name: String, model: java.util.List[_],
                                        idx: Int): (TableElement, Int) =
  if (idx >= model.size()) (null, -1)
  else model.get(idx) match {
    case e@TableElement(n, _) if name == n => (e, idx)
    case _ => findElementIndex(name, model, idx + 1)
  }

  /**
    * Obtains the icon to represent the specified archive state.
    *
    * @param state the state
    * @return the icon for this state
    */
  private def iconForState(state: HttpArchiveState): AnyRef =
    state match {
      case HttpArchiveStateInitializing => statusHelper.iconPending
      case s =>
        if (s.isActive) statusHelper.iconActive else statusHelper.iconInactive
    }

  /**
    * Determines the name of the currently selected realm.
    *
    * @param index the selected index in the realms table
    * @return the name of this realm or null if there is no selection
    */
  private def currentRealmName(index: Int): String =
    if (index < 0) null
    else tabRealms.getModel.get(index).asInstanceOf[TableElement].name
}

/**
  * Data class that represents an element in a table model.
  *
  * @param name the name to be displayed
  * @param icon the icon for this element
  */
private case class TableElement(@BeanProperty name: String, @BeanProperty icon: AnyRef)
