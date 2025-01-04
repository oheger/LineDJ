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

package de.oliver_heger.linedj.platform.app.hide.impl

import de.oliver_heger.linedj.platform.app.ApplicationManager.{ApplicationRegistered, ApplicationRemoved, ApplicationTitleUpdated}
import de.oliver_heger.linedj.platform.app.hide.*
import de.oliver_heger.linedj.platform.app.{BaseApplicationManager, ClientApplication, ClientApplicationContext}
import de.oliver_heger.linedj.platform.bus.ConsumerSupport
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.ConsumerFunction
import net.sf.jguiraffe.gui.builder.window.Window
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.Actor.Receive
import org.osgi.service.component.ComponentContext

import java.awt.SystemTray

/**
  * A specialized ''ApplicationManager'' implementation which keeps track on
  * the visibility state of all installed applications.
  *
  * This application manager implementation monitors window closing and
  * application exit events. When such an event is received, the window of the
  * affected application is just hidden. It can later be made visible again.
  * The shutdown of the platform can be triggered by sending a special shutdown
  * message.
  *
  * This application manager is intended to be used in deployments with
  * multiple LineDJ applications. The user can work with some applications and
  * hide others which are currently not used. An example scenario could be a
  * music player application that is deployed together with a playlist manager;
  * the latter is only needed if a new playlist is to be created and can
  * otherwise remain invisible.
  *
  * To be of real value for the user, this application manager typically has to
  * be combined with another component that displays a list of all applications
  * available, so that the user can switch to a specific one. This component
  * should also offer a means to close the whole platform. How such a component
  * looks like, is not specified by this application manager. However, it
  * defines a number of messages through which an interaction is possible; the
  * listing component can register itself as consumer for
  * [[de.oliver_heger.linedj.platform.app.hide.ApplicationWindowState]]
  * notifications and can thus keep track on changes on visible applications.
  * On the other hand, it can send messages to the manager to show or hide
  * application windows. The state of visible application windows can also be
  * persisted, so that it can be restored after a restart of the application.
  *
  * It is possible to declare some applications as "main applications" (refer to
  * [[AppConfigWindowConfiguration]] for further details). If the user closes
  * the window of such an application, the whole platform is shut down. This
  * application manager can also run in a mode in which it is not possible to
  * close non-main windows. This may be necessary if no component is available
  * to list the applications; in this case, closing/hiding a window could not
  * be reverted anymore. Since the system tray is typically used by a listing
  * component, this mode is enabled by default if the system tray is
  * unavailable on the current platform.
  *
  * @param ignoreCloseNonMainApps flag whether notifications for closing
  *                               windows or shutting down apps for non-main
  *                               applications should be ignored
  */
class WindowHidingApplicationManager(ignoreCloseNonMainApps: Boolean) extends BaseApplicationManager
  with ConsumerSupport[ApplicationWindowState, AnyRef]:
  def this() = this(ignoreCloseNonMainApps = !SystemTray.isSupported)

  /** The logger. */
  private val log = LogManager.getLogger(getClass)

  /** A set with the application currently visible. */
  private var visibleApplications = Set.empty[ClientApplication]

  /** Stores the window management configuration. */
  private var optWindowConfig: Option[ApplicationWindowConfiguration] = None

  /**
    * The default key used by this extension. No grouping is supported, so this
    * can be an arbitrary object.
    */
  override val defaultKey: AnyRef = new Object

  /**
    * @inheritdoc This implementation checks whether the management
    *             configuration contains information about the window
    *             visibility state.
    */
  override def initApplicationContext(context: ClientApplicationContext): Unit =
    super.initApplicationContext(context)
    optWindowConfig = AppConfigWindowConfiguration(context.managementConfiguration)
    if optWindowConfig.isDefined then
      log.info("Using window configuration from management app.")

  /**
    * Activates this component. This method is called by the declarative
    * services runtime. It delegates to the ''setUp()'' method from the super
    * class.
    *
    * @param compCtx the component context (unused)
    */
  def activate(compCtx: ComponentContext): Unit =
    setUp()
    log.info("Activated WindowHidingApplicationManager.")

  /**
    * Deactivates this component. This method is called by the declarative
    * services runtime. It delegates to the ''tearDown()'' method from the
    * super class.
    *
    * @param compCtx the component context (unused)
    */
  def deactivate(compCtx: ComponentContext): Unit =
    tearDown()
    log.info("Deactivated WindowHidingApplicationManager.")

  /**
    * @inheritdoc This implementation passes the current state to the new
    *             consumer.
    */
  override def onConsumerAdded(cons: ConsumerFunction[ApplicationWindowState], key: AnyRef,
                               first: Boolean): Unit =
    cons(applicationWindowState)

  /**
    * @inheritdoc This implementation reacts on specific messages related to
    *             applications and their main windows.
    */
  override protected def onMessage: Receive =
    case reg: WindowStateConsumerRegistration =>
      addConsumer(reg)

    case WindowStateConsumerUnregistration(id) =>
      removeConsumer(id)

    case ApplicationRegistered(app) =>
      if optWindowConfig.isEmpty then
        optWindowConfig = updateWindowConfig(app)
      if isAppVisible(windowConfig, app) then
        visibleApplications += app
      else
        app showMainWindow false
      notifyConsumers()

    case ApplicationRemoved(app) =>
      visibleApplications -= app
      notifyConsumers()

    case ApplicationTitleUpdated(_, _) =>
      notifyConsumers()

    case HideApplicationWindow(app) =>
      if visibleApplications contains app then
        log.info("Hiding application window {}.", app)
        visibleApplications -= app
        updateAppVisibility(app, state = false)

    case ShowApplicationWindow(app) =>
      if !visibleApplications.contains(app) && getApplications.exists(_ == app) then
        log.info("Showing application window {}.", app)
        visibleApplications += app
        updateAppVisibility(app, state = true)

    case ExitPlatform(applicationManager) =>
      log.info("Received ExitPlatform message.")
      if this eq applicationManager then triggerShutdown()
      else log.warn("Ignoring invalid ExitPlatform message!")

  /**
    * @inheritdoc This implementation sends a message to hide this
    *             application's main window.
    */
  override protected def onApplicationShutdown(app: ClientApplication): Unit =
    handleAppShutdownOrWindowClosing(app)

  /**
    * @inheritdoc This implementation searches for the application to which
    *             this window belongs. If it is found, a message is sent to
    *             hide this applications's main window.
    */
  override protected def onWindowClosing(window: Window): Unit =
    val optApp = getApplications.find(_.optMainWindow.orNull == window)
    optApp foreach handleAppShutdownOrWindowClosing

  /**
    * Handles notifications about an application shutdown or the closing of an
    * application window. If the application is a main application, the whole
    * platform is shut down; otherwise, only the window is made invisible.
    *
    * @param app the affected application
    */
  private def handleAppShutdownOrWindowClosing(app: ClientApplication): Unit =
    if windowConfig.isMainApplication(app) then
      triggerShutdown()
    else if !ignoreCloseNonMainApps then
      publishMessage(HideApplicationWindow(app))

  /**
    * Updates the visibility state of the given application. The window is
    * shown or hidden, the window configuration is updated, and consumers are
    * notified.
    *
    * @param app   the application
    * @param state the new visibility state
    */
  private def updateAppVisibility(app: ClientApplication, state: Boolean): Unit =
    app showMainWindow state
    windowConfig.setWindowVisible(app, state)
    notifyConsumers()

  /**
    * Returns the current window configuration. If one has been set, it is
    * returned. Otherwise, the default implementation is used.
    *
    * @return the window configuration
    */
  private def windowConfig: ApplicationWindowConfiguration =
    optWindowConfig getOrElse DefaultApplicationWindowConfiguration

  /**
    * Updates the current window configuration with the one provided by the
    * given application if any. If a configuration is defined, the visibility
    * states of all available applications are updated according to the new
    * configuration.
    *
    * @param app the application
    * @return an option with the new window configuration
    */
  private def updateWindowConfig(app: ClientApplication):
  Option[ApplicationWindowConfiguration] =
    val config = AppConfigWindowConfiguration(app.getUserConfiguration)
    config foreach { c =>
      log.info("Using window configuration from {}.", app.appName)
      val appsToUpdate = getApplications.filterNot(_ == app)
        .map(a => (a, visibleApplications contains a))
        .filter(t => t._2 != isAppVisible(c, t._1))
      visibleApplications = appsToUpdate.foldLeft(visibleApplications) { (s, t) =>
        val (app, state) = t
        app.showMainWindow(!state)
        if state then s - app
        else s + app
      }
    }
    config

  /**
    * Consults the given configuration to determine whether the specified
    * application should be visible.
    *
    * @param config the window configuration
    * @param app    the application
    * @return a flag whether this app should be visible according to the
    *         configuration
    */
  private def isAppVisible(config: ApplicationWindowConfiguration,
                           app: ClientApplication): Boolean =
    config.isWindowVisible(app) || config.isMainApplication(app)

  /**
    * Notifies all registered consumers about an updated application window
    * state.
    */
  private def notifyConsumers(): Unit =
    invokeConsumers(applicationWindowState)

  /**
    * Returns an ''ApplicationWindowState'' object with the current status.
    *
    * @return the current application window state
    */
  private def applicationWindowState: ApplicationWindowState =
    ApplicationWindowState(getApplicationsWithTitles, visibleApplications)

/**
  * A default implementation of ''ApplicationWindowConfiguration''.
  *
  * This implementation is used if no other configuration has been provided.
  * It marks every application as visible, do not support main applications,
  * and ignores updates of the visibility state.
  */
private object DefaultApplicationWindowConfiguration
  extends ApplicationWindowConfiguration:
  /**
    * @inheritdoc This implementation always returns '''true'''.
    */
  override def isWindowVisible(app: ClientApplication): Boolean = true

  /**
    * @inheritdoc This is just an empty dummy implementation.
    */
  override def setWindowVisible(app: ClientApplication, visible: Boolean): Unit = {}

  /**
    * @inheritdoc This implementation always returns '''false'''. (Actually,
    *             with ''isWindowVisible()'' returning '''true''', it will
    *             never be invoked.)
    */
  override def isMainApplication(app: ClientApplication): Boolean = false
