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

package de.oliver_heger.linedj.platform.app

import net.sf.jguiraffe.di.MutableBeanStore
import net.sf.jguiraffe.di.impl.providers.ConstantBeanProvider
import net.sf.jguiraffe.gui.app.{Application, ApplicationContext}
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer
import net.sf.jguiraffe.gui.builder.window.{Window, WindowEvent, WindowListener}
import org.osgi.service.component.ComponentContext

object ClientApplication:
  /** The prefix used by all beans managed by this application. */
  val BeanPrefix = "LineDJ_"

  /** The bean for the client-side actor system. */
  val BeanActorSystem: String = BeanPrefix + "ActorSystem"

  /** The bean for the actor factory. */
  val BeanActorFactory: String = BeanPrefix + "ActorFactory"

  /** The bean for the message bus. */
  val BeanMessageBus: String = BeanPrefix + "MessageBus"

  /** The bean for the media facade. */
  val BeanMediaFacade: String = BeanPrefix + "MediaFacade"

  /** The bean for the whole client application context. */
  val BeanClientApplicationContext: String = BeanPrefix + "ClientApplicationContext"

  /**
    * The bean for the message bus registration. If a bean with this name is
    * available in the bean context for the main window, it is requested and
    * thus initialized automatically.
    */
  val BeanMessageBusRegistration: String = BeanPrefix + "messageBusRegistration"

  /**
    * The bean for the consumer registration (for extensions of the media
    * archive interface). If a bean with this name is available in the bean
    * context for the main window, it is requested and thus initialized
    * automatically.
    */
  val BeanConsumerRegistration: String = BeanPrefix + "consumerRegistration"

  /**
    * The name of a blocking dispatcher in the actor system configuration.
    * Client applications can use this dispatcher for actors that do blocking
    * operations of any kind.
    */
  val BlockingDispatcherName = "blocking-dispatcher"

/**
  * A class representing a visual client application running on the LineDJ
  * (OSGi) platform.
  *
  * This class is more or less a regular JGUIraffe application with an
  * arbitrary main window. It serves as base class for all visual LineDJ
  * applications. In this role, it provides additional functionality that can
  * be used by derived classes:
  *
  * '''Management of central beans'''
  *
  * At startup, an instance obtains some central platform beans from the
  * [[ClientApplicationContext]] and makes them available in its own
  * ''BeanContext''. These are the following beans:
  *
  *  - ''LineDJ_ActorSystem'': The actor system used by the platform.
  *  - ''LineDJ_ActorFactory'': The helper object for creating new actors.
  *  - ''LineDJ_MessageBus'': The UI message bus.
  *  - ''LineDJ_MediaFacade'': The facade to the media archive.
  *  - ''LineDJ_ClientApplicationContext'': The [[ClientApplicationContext]]
  * itself.
  *
  * There is also some support for registering listeners on the system-wide
  * message bus: If the Jelly script for the main window defines a bean named
  * ''LineDJ_messageBusRegistration'', this bean is fetched during startup and
  * thus initialized. This typically causes the registration of the listeners
  * used by this application.
  *
  * '''Life-cycle management'''
  *
  * The class implements the typical life-cycle hooks of a JGUIraffe
  * application to make sure that state related to the LineDJ platform gets
  * correctly initialized. It also depends on a service of type
  * [[ApplicationManager]] which must be initialized by concrete subclasses
  * (typically via a corresponding declarative services configuration). The
  * ''ApplicationManager'' is provided to subclasses; the following
  * interactions are implemented with it:
  *
  *  - The application is registered as service in the OSGi registry. This is
  * required to let it take part in the platform's application management,
  * including shutdown handling.
  *  - The ''ApplicationManager'' is notified when this application has been
  * fully initialized (and the Jelly script for the main window has been
  * executed).
  *
  * '''Further notes'''
  *
  * This class is intended to be used as a declarative services component. It
  * needs static references to a [[ClientApplicationContext]] and an
  * [[ApplicationManager]] service; with these services a full integration into
  * the LineDJ platform is achieved.
  *
  * While this class is fully functional, in order to implement a valid
  * declarative services component, it has to be extended, and the name of the
  * configuration file has to be passed to the constructor.
  *
  * @param appName the name of this application
  */
class ClientApplication(val appName: String) extends Application with ClientContextSupport:
  this: ApplicationStartup =>

  import ClientApplication._

  /** The application manager. */
  private var applicationManagerField: ApplicationManager = _

  /** A flag whether the main window has already been opened. */
  private var mainWindowOpened = false

  /**
    * The visible flag of the main window. This flag is used if the window's
    * visible state is changed before it is opened.
    */
  private var mainWindowVisible = true

  /**
    * Initializes the ''ApplicationManager'' service. This method is called by
    * the SCR.
    * @param appMan the ''ApplicationManager''
    */
  def initApplicationManager(appMan: ApplicationManager): Unit =
    applicationManagerField = appMan

  /**
    * Returns the ''ApplicationManager''. This object is available afther the
    * initialization of this application.
    * @return the ''ApplicationManager''
    */
  def applicationManager: ApplicationManager = applicationManagerField

  /**
    * @inheritdoc This implementation starts the application using the mixed
    *             in [[ApplicationStartup]] implementation.
    */
  override def activate(compContext: ComponentContext): Unit =
    startApplication(this, appName)

  /**
    * @inheritdoc This implementation ensures that the current application is
    *             gracefully shutdown.
    */
  override def deactivate(componentContext: ComponentContext): Unit =
    shutdown(true)
    super.deactivate(componentContext)

  /**
    * Updates the title of this application. This method should be used to
    * change the title of the main window of this application. It not only
    * updates the title but also informs the [[ApplicationManager]] about the
    * new title. '''Note:''' This method can only be called on the event
    * dispatch thread!
 *
    * @param newTitle the new title of this application's main window
    */
  def updateTitle(newTitle: String): Unit =
    optMainWindow foreach { w =>
      w setTitle newTitle
      applicationManager.applicationTitleUpdated(this, newTitle)
    }

  /**
    * Allows changing the visibility of the main window. If the window is
    * already open, its visibility is changed immediately. Otherwise, the
    * visibility state is stored and applied when the window is opened.
    * This method must be called in the event dispatch thread.
    * @param display the new visibility state of the main window
    */
  def showMainWindow(display: Boolean): Unit =
    if mainWindowOpened then
      mainWindow setVisible display
    else
      mainWindowVisible = display

  /**
    * Returns an option for this application's main window.
    * @return the option for the main window
    */
  def optMainWindow: Option[Window] =
    Option(mainWindow)

  /**
    * Returns this application's main window.
    * @return this application's main window (may be '''null''')
    */
  def mainWindow: Window =
    getApplicationContext.getMainWindow

  /**
    * @inheritdoc This implementation adds some beans defined in the client
    *             application context to this application's ''BeanContext'',
    *             so that they are available everywhere in this application.
    */
  override def createApplicationContext(): ApplicationContext =
    val appCtx = super.createApplicationContext()
    addBeanDuringApplicationStartup(BeanActorSystem, clientApplicationContext.actorSystem)
    addBeanDuringApplicationStartup(BeanActorFactory, clientApplicationContext.actorFactory)
    addBeanDuringApplicationStartup(BeanMessageBus, clientApplicationContext.messageBus)
    addBeanDuringApplicationStartup(BeanMediaFacade, clientApplicationContext.mediaFacade)
    addBeanDuringApplicationStartup(BeanClientApplicationContext, clientApplicationContext)

    // needs to be added to the bean store with highest priority
    val topBeanStore = appCtx.getBeanContext.getDefaultBeanStore.asInstanceOf[MutableBeanStore]
    topBeanStore.addBeanProvider("jguiraffe.windowManager",
      ConstantBeanProvider.getInstance(clientApplicationContext.windowManager))
    appCtx

  /**
    * @inheritdoc This implementation queries the status of the remote message
    *             bus after the UI has been initialized. This causes a status
    *             message to be sent which triggers some further initialization
    *             of the UI.
    */
  override def initGUI(appCtx: ApplicationContext): Unit =
    super.initGUI(appCtx)
    if getMainWindowBeanContext != null then
      initializeBeanIfPresent(BeanMessageBusRegistration)
      initializeBeanIfPresent(BeanConsumerRegistration)
    optMainWindow foreach (_.addWindowListener(createWindowOpenedListener()))

    applicationManager registerApplication this

  /**
    * Checks whether the bean with the specified name is contained in the
    * bean context of the main window. If so, it is requested. This triggers an
    * automatic initialization of the bean. Using this mechanism, some special
    * beans are created directly after application startup, for instance to
    * perform a registration of message bus listeners automatically.
    *
    * @param name the name of the bean in question
    */
  private def initializeBeanIfPresent(name: String): Unit =
    if getMainWindowBeanContext containsBean name then
      getMainWindowBeanContext getBean name

  /**
    * Creates a window listener which notifies this application when its main
    * window is opened. This is needed because some manipulations on the main
    * window can only be done if it has already been opened.
    * @return the window listener
    */
  private def createWindowOpenedListener(): WindowListener =
    new WindowListener:
      override def windowDeiconified(event: WindowEvent): Unit = {}

      override def windowClosing(event: WindowEvent): Unit = {}

      override def windowClosed(event: WindowEvent): Unit = {}

      override def windowActivated(event: WindowEvent): Unit = {}

      override def windowOpened(event: WindowEvent): Unit =
        onWindowOpened()

      override def windowDeactivated(event: WindowEvent): Unit = {}

      override def windowIconified(event: WindowEvent): Unit = {}

  /**
    * Callback which is invoked when this application's main window is
    * opened. If necessary, the visibility state of the window has to be
    * changed.
    */
  private def onWindowOpened(): Unit =
    if !mainWindowOpened then
      mainWindowOpened = true
      if !mainWindowVisible then
        val sync = getApplicationContext.getBeanContext
          .getBean(Application.BEAN_GUI_SYNCHRONIZER).asInstanceOf[GUISynchronizer]
        sync.asyncInvoke(() => {
          mainWindow setVisible false
        })
