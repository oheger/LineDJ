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

package de.oliver_heger.linedj.platform.app

import de.oliver_heger.linedj.platform.comm.MessageBus
import net.sf.jguiraffe.gui.app.{Application, ApplicationContext, ApplicationShutdownListener}
import net.sf.jguiraffe.gui.builder.window.{Window, WindowClosingStrategy}
import org.apache.pekko.actor.Actor.Receive
import org.mockito.ArgumentMatchers.{any, eq => eqArg}
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''BaseApplicationManager''.
  */
class BaseApplicationManagerSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  /**
    * Creates a basic mock for an application. This mock also has an
    * application context.
    *
    * @return the mock application
    */
  private def createApplicationMock(): ClientApplication =
    val app = mock[ClientApplication]
    val appCtx = mock[ApplicationContext]
    when(app.getApplicationContext).thenReturn(appCtx)
    app

  /**
    * Creates a mock for an application which is initialized with a main
    * window.
    *
    * @return the mock application
    */
  private def createApplicationMockWithWindow(): ClientApplication =
    val app = mock[ClientApplication]
    val appCtx = mock[ApplicationContext]
    val window = mock[Window]
    when(app.getApplicationContext).thenReturn(appCtx)
    when(appCtx.getMainWindow).thenReturn(window)
    when(app.optMainWindow).thenReturn(Some(window))
    app

  /**
    * Obtains an ''ApplicationShutdownListener'' that has been registered at a
    * mock application.
    *
    * @param app the mock application
    * @return the registered shutdown listener
    */
  private def fetchRegisteredShutdownListener(app: Application):
  ApplicationShutdownListener =
    val captor = ArgumentCaptor.forClass(classOf[ApplicationShutdownListener])
    verify(app).addShutdownListener(captor.capture())
    captor.getValue

  /**
    * Obtains a ''WindowClosingStrategy'' that has been registered at the given
    * application's main window.
    *
    * @param app the mock application
    * @return the registered window closing strategy
    */
  private def fetchRegisteredWindowClosingStrategy(app: Application):
  WindowClosingStrategy =
    val captor = ArgumentCaptor.forClass(classOf[WindowClosingStrategy])
    verify(app.getApplicationContext.getMainWindow).setWindowClosingStrategy(captor.capture())
    captor.getValue

  "A BaseApplicationManager" should "create a correct app service manager" in:
    val helper = new ApplicationManagerTestHelper(mockServiceManagers = false)

    helper.checkServiceManager(helper.manager.applicationServiceManager,
      classOf[ClientApplication])

  it should "create a correct shutdown listener service manager" in:
    val helper = new ApplicationManagerTestHelper(mockServiceManagers = false)

    helper.checkServiceManager(helper.manager.shutdownListenerManager,
      classOf[ShutdownListener])

  it should "allow adding new shutdown listeners" in:
    val listener = mock[ShutdownListener]
    val helper = new ApplicationManagerTestHelper

    helper.manager addShutdownListener listener
    verify(helper.listenerServiceManager).addService(listener)

  it should "allow removing a shutdown listener" in:
    val listener = mock[ShutdownListener]
    val helper = new ApplicationManagerTestHelper

    helper.manager removeShutdownListener listener
    verify(helper.listenerServiceManager).removeService(listener)

  it should "allow removing an application" in:
    val app = mock[ClientApplication]
    val helper = new ApplicationManagerTestHelper

    helper.manager removeApplication app
    verify(helper.appServiceManager).removeService(app)

  it should "send a notification if an application is removed" in:
    val app = mock[ClientApplication]
    val helper = new ApplicationManagerTestHelper

    helper.manager removeApplication app
    verify(helper.bus).publish(ApplicationManager.ApplicationRemoved(app))

  it should "add a newly initialized application" in:
    val app = createApplicationMockWithWindow()
    val helper = new ApplicationManagerTestHelper

    helper applicationAdded app

  it should "init an application with a shutdown listener" in:
    val app = createApplicationMockWithWindow()
    val helper = new ApplicationManagerTestHelper

    helper applicationAdded app
    val listener = fetchRegisteredShutdownListener(app)
    listener.canShutdown(app) shouldBe false
    helper.manager.shutdownApps should be(List(app))

  it should "register a shutdown listener that ignores a shutdown callback" in:
    val app = createApplicationMockWithWindow()
    val helper = new ApplicationManagerTestHelper

    helper applicationAdded app
    val listener = fetchRegisteredShutdownListener(app)
    reset(app)
    listener shutdown app
    verifyNoInteractions(app)

  it should "init an application with a window closing strategy" in:
    val app = createApplicationMockWithWindow()
    val helper = new ApplicationManagerTestHelper

    helper applicationAdded app
    val strategy = fetchRegisteredWindowClosingStrategy(app)
    val window = app.getApplicationContext.getMainWindow
    strategy.canClose(window) shouldBe false
    helper.manager.closedWindows should be(List(window))

  it should "handle an application with no main window" in:
    val app = createApplicationMockWithWindow()
    when(app.optMainWindow).thenReturn(None)
    when(app.getApplicationContext.getMainWindow).thenReturn(null)
    val helper = new ApplicationManagerTestHelper

    helper applicationAdded app

  it should "init an application with a dummy exit handler" in:
    val app = createApplicationMockWithWindow()
    val helper = new ApplicationManagerTestHelper

    helper applicationAdded app
    val captor = ArgumentCaptor.forClass(classOf[Runnable])
    verify(app).setExitHandler(captor.capture())
    captor.getValue.run()  // should do nothing

  it should "send a notification message when an application is added" in:
    val app = createApplicationMockWithWindow()
    val helper = new ApplicationManagerTestHelper

    helper applicationAdded app
    verify(helper.bus).publish(ApplicationManager.ApplicationRegistered(app))

  it should "correctly tear down the application manager" in:
    val helper = new ApplicationManagerTestHelper

    helper.manager.tearDown()
    verify(helper.bus).removeListener(helper.MessageBusRegistrationID)
    verify(helper.appServiceManager).shutdown()
    verify(helper.listenerServiceManager).shutdown()

  it should "call shutdown listeners when triggering a platform shutdown" in:
    val helper = new ApplicationManagerTestHelper

    helper.manager.triggerShutdown()
    val captor = ArgumentCaptor.forClass(
      classOf[UIServiceManager.ProcessFunc[ShutdownListener]])
    verify(helper.listenerServiceManager).processServices(captor.capture())

    val l1, l2 = mock[ShutdownListener]
    when(l1.onShutdown()).thenReturn(true)
    when(l2.onShutdown()).thenReturn(true)
    val listeners = List(l1, l2)
    val optMsg = captor.getValue.apply(listeners)
    listeners foreach (verify(_).onShutdown())
    optMsg.get should be(BaseApplicationManager.ShutdownApplications)

  it should "abort shutdown if a listener vetos" in:
    val helper = new ApplicationManagerTestHelper

    helper.manager.triggerShutdown()
    val captor = ArgumentCaptor.forClass(
      classOf[UIServiceManager.ProcessFunc[ShutdownListener]])
    verify(helper.listenerServiceManager).processServices(captor.capture())

    val l1, l2, l3 = mock[ShutdownListener]
    when(l1.onShutdown()).thenReturn(true)
    when(l2.onShutdown()).thenReturn(false)
    val listeners = List(l1, l2, l3)
    captor.getValue.apply(listeners) shouldBe empty
    verify(l3, never()).onShutdown()

  it should "shutdown all applications" in:
    val app = createApplicationMockWithWindow()
    val helper = new ApplicationManagerTestHelper
    helper applicationAdded app
    val listener = fetchRegisteredShutdownListener(app)

    helper sendMessage BaseApplicationManager.ShutdownApplications
    val captor = ArgumentCaptor.forClass(
      classOf[UIServiceManager.ProcessFunc[Application]])
    verify(helper.appServiceManager).processServices(captor.capture())
    val apps = List(mock[Application], mock[Application])
    val optMsg = captor.getValue.apply(apps)
    apps foreach { a =>
      val io = Mockito.inOrder(a)
      io.verify(a).removeShutdownListener(listener)
      io.verify(a).shutdown()
    }
    optMsg.get should be(ShutdownHandler.Shutdown(helper.appContext))

  it should "enable message processing for derived classes" in:
    val Message = "Ping"
    val helper = new ApplicationManagerTestHelper(enableMessaging = true)

    helper sendMessage Message
    helper.manager.textMessage should be(Message)

  it should "send a notification if an application's title is updated" in:
    val app = createApplicationMockWithWindow()
    val Title = "Updated Application Title"
    val helper = new ApplicationManagerTestHelper

    helper.manager.applicationTitleUpdated(app, Title)
    verify(helper.bus).publish(ApplicationManager.ApplicationTitleUpdated(app, Title))

  it should "return a collection of existing applications" in:
    val apps = List(createApplicationMockWithWindow(), createApplicationMockWithWindow())
    val helper = new ApplicationManagerTestHelper
    when(helper.appServiceManager.services).thenReturn(apps)

    helper.manager.getApplications should be(apps)

  it should "return a collection of applications and their titles" in:
    val apps = List(createApplicationMockWithWindow(),
      createApplicationMockWithWindow())
    val titles = List("App1", "Another App")
    val appTitles = apps zip titles
    appTitles foreach { t =>
      when(t._1.getApplicationContext.getMainWindow.getTitle).thenReturn(t._2)
    }
    val helper = new ApplicationManagerTestHelper
    when(helper.appServiceManager.services).thenReturn(apps)

    helper.manager.getApplicationsWithTitles should be(appTitles)

  it should "handle apps without window when querying apps with titles" in:
    val app = createApplicationMock()
    when(app.optMainWindow).thenReturn(None)
    val helper = new ApplicationManagerTestHelper
    when(helper.appServiceManager.services).thenReturn(List(app))

    val result = helper.manager.getApplicationsWithTitles
    result should have size 1
    result.head._1 should be(app)
    result.head._2 should be(null)

  /**
    * A test helper class managing all dependencies of the manager to be
    * tested.
    *
    * @param mockServiceManagers a flag whether mock service managers should
    *                            be used
    * @param enableMessaging     a flag whether the test manager should have its
    *                            own messaging function
    */
  private class ApplicationManagerTestHelper(mockServiceManagers: Boolean = true,
                                             enableMessaging: Boolean = false):
    /** Registration ID for the message bus. */
    val MessageBusRegistrationID = 20161217

    /** A mock for the manager for application services. */
    val appServiceManager: UIServiceManager[ClientApplication] =
      mock[UIServiceManager[ClientApplication]]

    /** A mock for the manager for shutdown listener services. */
    val listenerServiceManager: UIServiceManager[ShutdownListener] =
      mock[UIServiceManager[ShutdownListener]]

    /** A mock for the message bus. */
    val bus: MessageBus = mock[MessageBus]

    /** A mock for the application context. */
    val appContext: ClientApplicationContext = createApplicationContext()

    /** The manager to be tested. */
    val manager: ApplicationManagerImpl = createApplicationManager()

    /** The message bus listener registered by the manager. */
    private lazy val busListeners = fetchMessageBusListener()

    /**
      * Checks whether a service manager has been correctly initialized.
      *
      * @param sm     the manager to check
      * @param svcCls the expected service class
      */
    def checkServiceManager(sm: UIServiceManager[_], svcCls: Class[_]): Unit =
      sm.serviceClass should be(svcCls)
      sm.messageBus should be(bus)

    /**
      * Sends the specified message via the message bus to the test manager.
      *
      * @param msg the message
      * @return this test helper
      */
    def sendMessage(msg: Any): ApplicationManagerTestHelper =
      val optListener = busListeners.find(_.isDefinedAt(msg))
      optListener shouldBe defined
      optListener.get.apply(msg)
      this

    /**
      * Simulates adding of an application and verifies that the application
      * manager has been invoked correctly. The manipulation function passed
      * to the manager is returned.
      *
      * @param app the application to be added
      * @return the manipulation function
      */
    def applicationAdded(app: ClientApplication): ClientApplication => ClientApplication =
      manager registerApplication app
      val captor = ArgumentCaptor.forClass(
        classOf[Option[ClientApplication => ClientApplication]])
      verify(appServiceManager).addService(eqArg(app), captor.capture())
      val func = captor.getValue.get
      func(app) should be(app)
      func

    /**
      * Creates a mock for the client application context.
      *
      * @return the mock for the context
      */
    private def createApplicationContext():
    ClientApplicationContext =
      val context = mock[ClientApplicationContext]
      when(context.messageBus).thenReturn(bus)
      when(bus.registerListener(any(classOf[Receive]))).thenReturn(MessageBusRegistrationID)
      context

    /**
      * Creates and initializes the test instance.
      *
      * @return the test instance
      */
    private def createApplicationManager(): ApplicationManagerImpl =
      val man = new ApplicationManagerImpl(appServiceManager, listenerServiceManager,
        mockServiceManagers, enableMessaging)
      man initApplicationContext appContext
      man.setUp()
      man

    /**
      * Obtains the message bus listeners that have been registered during
      * initialization of the test manager. This can then be used to pass
      * messages to the manager. Note that there are multiple listeners, also
      * for the UI managers.
      *
      * @return the message bus listener function
      */
    private def fetchMessageBusListener(): Iterable[Receive] =
      val captor = ArgumentCaptor.forClass(classOf[Receive])
      verify(bus, atLeastOnce()).registerListener(captor.capture())
      import scala.jdk.CollectionConverters._
      captor.getAllValues.asScala

  /**
    * A test implementation of an application manager.
    *
    * @param mockAppManager      can be used to override the app manager
    * @param mockListenerManager can be used to override the listener manager
    * @param mockManagers        flag whether managers should be mocked
    * @param enableMessaging flag whether a custom message function should be used
    */
  private class ApplicationManagerImpl(mockAppManager: UIServiceManager[ClientApplication],
                                       mockListenerManager: UIServiceManager[ShutdownListener],
                                       mockManagers: Boolean, enableMessaging: Boolean)
    extends BaseApplicationManager:
    /** Stores a list with applications passed to onApplicationShutdown(). */
    var shutdownApps: List[Application] = List.empty[Application]

    /** Stores a list with windows passed to onWindowClosing(). */
    var closedWindows: List[Window] = List.empty[Window]

    /** Stores a text message received via the message bus. */
    var textMessage: String = _

    /**
      * Just increase visibility.
      */
    override def triggerShutdown(): Unit = super.triggerShutdown()

    /**
      * @inheritdoc Tracks this invocation.
      */
    override protected def onApplicationShutdown(app: ClientApplication): Unit =
      super.onApplicationShutdown(app)
      shutdownApps = app :: shutdownApps

    /**
      * @inheritdoc Tracks this invocation.
      */
    override protected def onWindowClosing(window: Window): Unit =
      super.onWindowClosing(window)
      closedWindows = window :: closedWindows

    /**
      * @inheritdoc Either handles a string message or calls the super method.
      */
    override protected def onMessage: Receive =
      if enableMessaging then customMessageProcessing
      else super.onMessage

    override private[app] def applicationServiceManager =
      if mockManagers then mockAppManager else super.applicationServiceManager

    override private[app] def shutdownListenerManager =
      if mockManagers then mockListenerManager else super.shutdownListenerManager

    /**
      * A custom message processing function.
      *
      * @return the receive function
      */
    private def customMessageProcessing: Receive =
      case s: String => textMessage = s

