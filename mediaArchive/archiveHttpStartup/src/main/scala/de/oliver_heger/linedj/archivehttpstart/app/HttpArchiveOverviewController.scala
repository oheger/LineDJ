/*
 * Copyright 2015-2023 The Developers Team.
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

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.archivehttpstart.app.HttpArchiveStates.{HttpArchiveState, HttpArchiveStateInitializing, HttpArchiveStateNoUnionArchive}
import de.oliver_heger.linedj.platform.comm.{MessageBus, MessageBusListener}
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import net.sf.jguiraffe.gui.builder.event.{FormChangeEvent, FormChangeListener}
import net.sf.jguiraffe.gui.builder.window.{WindowEvent, WindowListener}

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.beans.BeanProperty

object HttpArchiveOverviewController {
  /** Name of the login action. */
  private val ActionLogin = "actionLogin"

  /** Name of the logout action. */
  private val ActionLogout = "actionLogout"

  /** Name of the logout all action. */
  private val ActionLogoutAll = "actionLogoutAll"

  /** Name of the action to unlock an archive. */
  private val ActionUnlock = "actionUnlock"
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
  * @param messageBus        the message bus
  * @param configManager     the object managing the archive configuration
  * @param actionStore       the action manager
  * @param tabArchives       the handler for the table of archives
  * @param tabRealms         the handler for the table of realms
  * @param statusHelper      the helper object for archive states
  * @param refCurrentRealm   holds the current realm
  * @param refCurrentArchive holds the name of the current archive
  */
class HttpArchiveOverviewController(messageBus: MessageBus, configManager: HttpArchiveConfigManager,
                                    actionStore: ActionStore, tabArchives: TableHandler,
                                    tabRealms: TableHandler, statusHelper: ArchiveStatusHelper,
                                    refCurrentRealm: AtomicReference[ArchiveRealm],
                                    refCurrentArchive: AtomicReference[String])
  extends WindowListener with MessageBusListener with FormChangeListener {

  import HttpArchiveOverviewController._

  /** A mapping from realm names to the concrete objects. */
  private var realms: Map[String, ArchiveRealm] = _

  /**
    * Stores the realms for which credentials are available. Keys of the map
    * are the indices in the realms table. The values are the corresponding
    * realm names.
    */
  private var loggedInRealms = Map.empty[Int, String]

  /** Stores the names of the archives that have been unlocked. */
  private var unlockedArchives = Set.empty[String]

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
    configManager.archives foreach { e =>
      tabArchives.getModel.add(TableElement(e._1, statusHelper.iconInactive,
        if (e._2.encrypted) statusHelper.iconLocked else null))
    }
    realms = configManager.archives.values
      .map(archive => (archive.realm.name, archive.realm))
      .toMap
    val realmNames = realms.keys.toSet.toSeq.sorted
    realmNames foreach { r =>
      tabRealms.getModel.add(TableElement(r, statusHelper.iconInactive, null))
    }

    tabArchives.tableDataChanged()
    tabRealms.tableDataChanged()

    enableAction(ActionLogin, enabled = false)
    enableAction(ActionUnlock, enabled = false)
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
        tabArchives.getModel.set(idx, elem.copy(iconState = iconForState(state)))
        tabArchives.rowsUpdated(idx, idx)
        if (tabArchives.getSelectedIndex == idx) {
          statusHelper updateStatusLine state
        }
      }

    case LoginStateChanged(realm, optCred) =>
      val (elem, idx) = findRealmIndex(realm)
      if (idx >= 0) {
        val icon = if (optCred.isDefined) statusHelper.iconActive
        else statusHelper.iconInactive
        loggedInRealms = if (optCred.isDefined) loggedInRealms + (idx -> realm)
        else loggedInRealms - idx
        tabRealms.getModel.set(idx, elem.copy(iconState = icon))
        tabRealms.rowsUpdated(idx, idx)
        enableLogoutAllAction()
        if (idx == tabRealms.getSelectedIndex) {
          enableAction(ActionLogout, enabled = loggedInRealms.contains(idx))
        }
      }

    case LockStateChanged(name, optKey) =>
      if (isEncryptedArchive(name)) {
        val (elem, idx) = findArchiveIndex(name)
        unlockedArchives = if (optKey.isDefined) unlockedArchives + name
        else unlockedArchives - name
        val lockIcon = if (optKey.isDefined) statusHelper.iconUnlocked else statusHelper.iconLocked
        tabArchives.getModel.set(idx, elem.copy(iconCrypt = lockIcon))
        tabArchives.rowsUpdated(idx, idx)
        enableLogoutAllAction()
        if (idx == tabArchives.getSelectedIndex) {
          archiveTableStateChanged()
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
        archiveTableStateChanged()

      case `tabRealms` =>
        val index = tabRealms.getSelectedIndex
        enableAction(ActionLogin, enabled = index != -1)
        enableAction(ActionLogout, enabled = loggedInRealms contains index)
        refCurrentRealm.set(safeRealmByName(loggedInRealms.getOrElse(index, currentRealmName(index))))
    }
  }

  /**
    * Handles the action to logout the currently selected realm. If a realm is
    * currently selected, a message is sent on the message bus indicating that
    * its login state changed to ''no credentials available''. This causes the
    * application to discard the credentials associated with this realm.
    */
  def logoutCurrentRealm(): Unit = {
    val realm = refCurrentRealm.get()
    if (realm != null) {
      sendLogoutsForRealms(realm.name :: Nil)
      sendLocksForArchives(configManager.archivesForRealm(realm.name))
    }
  }

  /**
    * Handles the action to logout all currently logged in realms. For each
    * realm in state ''logged in'' a message is published on the message bus
    * to discard the current credentials.
    */
  def logoutAllRealms(): Unit = {
    sendLogoutsForRealms(loggedInRealms.values)
    sendLocksForArchives(configManager.archives.values)
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
    * Sends lock messages for all encrypted archives in the given sequence.
    *
    * @param archives the sequence of archives
    */
  private def sendLocksForArchives(archives: Iterable[HttpArchiveData]): Unit = {
    archives.filter(_.encrypted)
      .foreach { data =>
        messageBus publish LockStateChanged(data.config.archiveConfig.archiveName, None)
      }
  }

  /**
    * Sets the enabled state of the logout all action based on the current
    * state of logged in realms and unlocked archives.
    */
  private def enableLogoutAllAction(): Unit = {
    enableAction(ActionLogoutAll, unlockedArchives.nonEmpty || loggedInRealms.nonEmpty)
  }

  /**
    * Reacts on a change related to the table with archives. If necessary,
    * action states or the status line are updated.
    */
  private def archiveTableStateChanged(): Unit = {
    val index = tabArchives.getSelectedIndex
    if (index >= 0) {
      statusHelper.updateStatusLine(archiveStates(index))
      val archiveName = tabArchives.getModel.get(index).asInstanceOf[TableElement].name
      refCurrentArchive.set(archiveName)
      enableAction(ActionUnlock,
        enabled = isEncryptedArchive(archiveName) && !unlockedArchives.contains(archiveName))
    }
    else {
      statusHelper.clearStatusLine()
      refCurrentArchive.set(null)
      enableAction(ActionUnlock, enabled = false)
    }
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
  private def findRealmIndex(name: String): (TableElement, Int) =
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
      case e@TableElement(n, _, _) if name == n => (e, idx)
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

  /**
    * Returns the realm for the given name handling '''null''' names
    * gracefully.
    *
    * @param name the realm name
    * @return the object representing this realm
    */
  private def safeRealmByName(name: String): ArchiveRealm =
    if (name == null) null
    else realms(name)

  /**
    * Checks whether the archive with the given name is encrypted.
    *
    * @param name the name of the archive in question
    * @return a flag whether this archive is encrypted
    */
  private def isEncryptedArchive(name: String): Boolean =
    configManager.archives(name).encrypted
}

/**
  * Data class that represents an element in a table model.
  *
  * @param name      the name to be displayed
  * @param iconState the state icon for this element
  * @param iconCrypt the icon indicating the encryption state
  */
private case class TableElement(@BeanProperty name: String,
                                @BeanProperty iconState: AnyRef,
                                @BeanProperty iconCrypt: AnyRef)
