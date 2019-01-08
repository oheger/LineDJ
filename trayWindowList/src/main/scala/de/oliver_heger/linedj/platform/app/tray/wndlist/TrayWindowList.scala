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

package de.oliver_heger.linedj.platform.app.tray.wndlist

import java.awt.event.{ActionEvent, ActionListener, ItemEvent, ItemListener}
import java.awt.{CheckboxMenuItem, MenuItem, PopupMenu}
import java.util.ResourceBundle
import java.util.concurrent.atomic.AtomicReference
import javax.swing.ImageIcon

import de.oliver_heger.linedj.platform.app.{ApplicationManager, ClientApplication,
ClientApplicationContext}
import de.oliver_heger.linedj.platform.app.hide._
import de.oliver_heger.linedj.platform.bus.ComponentID
import org.osgi.service.component.ComponentContext

object TrayWindowList {
  /** The name of the resource bundle used by this class. */
  private val ResourceBundleName = "windowList-resources"

  /** Path to the default tray icon. */
  private val IconPath = "/icon.png"

  /** Resource key for the tool tip of the tray icon. */
  private val ResToolTip = "tool_tip"

  /** Resource key for the exit menu item. */
  private val ResExit = "exit_cmd"
}

/**
  * A class adding an icon to the system tray that allows controlling the
  * visibility state of all application windows currently installed on the
  * LineDJ platform.
  *
  * This class registers a consumer for the application window state. When a
  * change notification is received a popup menu is created listing the
  * window titles of all installed applications. On selecting such a menu item,
  * the visibility state of the corresponding application window is toggled.
  * With an additional menu item it is possible to exit the platform.
  *
  * This class is a declarative services component.
  *
  * @param trayHandler the object for accessing the system tray
  * @param sync        the object for synchronizing with the AWT thread
  */
class TrayWindowList(private[wndlist] val trayHandler: TrayHandler,
                     private[wndlist] val sync: TraySynchronizer) {

  import TrayWindowList._

  def this() = this(TrayHandlerImpl, new TraySynchronizer)

  /**
    * The consumer registration for the application window state.
    */
  val Registration: WindowStateConsumerRegistration =
    WindowStateConsumerRegistration(ComponentID(), windowStateChanged)

  /** Holds a reference to the added tray icon. */
  private val trayIconHandlerRef = new AtomicReference[TrayIconHandler]

  /** The client application context. */
  private var clientApplicationContext: ClientApplicationContext = _

  /** The application manager. */
  private var applicationManager: ApplicationManager = _

  /** The menu item for exiting the platform. */
  private var exitMenuItem: MenuItem = _

  /**
    * Initializes the ''ClientApplicationContext''. This method is invoked by
    * the SCR.
    *
    * @param clientContext the ''ClientApplicationContext''
    */
  def initClientApplicationContext(clientContext: ClientApplicationContext): Unit = {
    clientApplicationContext = clientContext
  }

  /**
    * Initializes the ''ApplicationManager'' reference. This method is called
    * by the SCR.
    *
    * @param appMan the ''ApplicationManager''
    */
  def initApplicationManager(appMan: ApplicationManager): Unit = {
    applicationManager = appMan
  }

  /**
    * Activates this component. This method is called by the SCR.
    *
    * @param componentContext the component context
    */
  def activate(componentContext: ComponentContext): Unit = {
    sync.schedule {
      val bundle = ResourceBundle getBundle ResourceBundleName
      val image = new ImageIcon(getClass.getResource(IconPath)).getImage
      trayHandler.addIcon(image, bundle.getString(ResToolTip)) foreach { h =>
        trayIconHandlerRef.set(h)
        exitMenuItem = createExitMenutItem(bundle)
        publish(Registration)
      }
    }
  }

  /**
    * Deactivates this component. This method is called by the SCR. If a tray
    * icon has been added successfully, it is now removed again.
    *
    * @param componentContext the component context
    */
  def deactivate(componentContext: ComponentContext): Unit = {
    val handler = trayIconHandlerRef.get()
    if (handler != null) {
      sync.schedule {
        handler.remove()
      }
      publish(WindowStateConsumerUnregistration(Registration.id))
    }
  }

  /**
    * The consumer function receiving updates of the application window state.
    *
    * @param state the updated state
    */
  private def windowStateChanged(state: ApplicationWindowState): Unit = {
    sync.schedule {
      val items = createItemsForApps(state)
      val popup = new PopupMenu
      items foreach popup.add
      popup.addSeparator()
      popup.add(exitMenuItem)
      trayIconHandlerRef.get() updateMenu popup
    }
  }

  /**
    * Creates menu items for the currently installed applications.
    *
    * @param state the current window state
    * @return a sequence with the menu items for the applications
    */
  private def createItemsForApps(state: ApplicationWindowState): Seq[CheckboxMenuItem] = {
    val sortedApps = state.appsWithTitle.toSeq.sortWith { (e1, e2) =>
      e1._2.compareToIgnoreCase(e2._2) < 0
    }
    sortedApps map { t =>
      createCheckboxItem(t._1, t._2, state.visibleApps contains t._1)
    }
  }

  /**
    * Creates a checkbox menu item for an application. Via this menu item the
    * window of this application can be shown or hidden.
    *
    * @param app     the client application
    * @param title   the title of the application window
    * @param visible flag whether the application is current visible
    * @return the menu item for this application
    */
  private def createCheckboxItem(app: ClientApplication, title: String,
                                 visible: Boolean): CheckboxMenuItem = {
    val item = new CheckboxMenuItem(title, visible)
    item.addItemListener(new ItemListener {
      override def itemStateChanged(e: ItemEvent): Unit = {
        val msg = if (e.getStateChange == ItemEvent.SELECTED) ShowApplicationWindow(app)
        else HideApplicationWindow(app)
        publish(msg)
      }
    })
    item
  }

  /**
    * Creates the menu item for exiting the platform.
    *
    * @param bundle the resource bundle
    * @return the exit menu item
    */
  private def createExitMenutItem(bundle: ResourceBundle): MenuItem = {
    val item = new MenuItem(bundle getString ResExit)
    item addActionListener new ActionListener {
      override def actionPerformed(e: ActionEvent): Unit = {
        publish(ExitPlatform(applicationManager))
      }
    }
    item
  }

  /**
    * Publishes the specified message on the message bus.
    *
    * @param msg the message
    */
  private def publish(msg: Any): Unit = {
    clientApplicationContext.messageBus publish msg
  }
}
