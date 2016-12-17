/*
 * Copyright 2015-2016 The Developers Team.
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

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.comm.MessageBus
import net.sf.jguiraffe.gui.app.{Application, ApplicationContext, ApplicationShutdownListener}
import net.sf.jguiraffe.gui.builder.window.{Window, WindowClosingStrategy}
import org.mockito.{ArgumentCaptor, Mockito}
import org.mockito.Mockito._
import org.mockito.Matchers.{any, eq => eqArg}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''BaseApplicationManager''.
  */
class BaseApplicationManagerSpec extends FlatSpec with Matchers with MockitoSugar {
  /**
    * Creates a mock for an application. The application also has a main
    * window.
    *
    * @return the mock application
    */
  private def createApplicationMock(): ClientApplication = {
    val app = mock[ClientApplication]
    val appCtx = mock[ApplicationContext]
    val window = mock[Window]
    when(app.getApplicationContext).thenReturn(appCtx)
    when(appCtx.getMainWindow).thenReturn(window)
    app
  }

  /**
    * Obtains an ''ApplicationShutdownListener'' that has been registered at a
    * mock application.
    *
    * @param app the mock application
    * @return the registered shutdown listener
    */
  private def fetchRegisteredShutdownListener(app: Application):
  ApplicationShutdownListener = {
    val captor = ArgumentCaptor.forClass(classOf[ApplicationShutdownListener])
    verify(app).addShutdownListener(captor.capture())
    captor.getValue
  }

  /**
    * Obtains a ''WindowClosingStrategy'' that has been registered at the given
    * application's main window.
    *
    * @param app the mock application
    * @return the registered window closing strategy
    */
  private def fetchRegisteredWindowClosingStrategy(app: Application):
  WindowClosingStrategy = {
    val captor = ArgumentCaptor.forClass(classOf[WindowClosingStrategy])
    verify(app.getApplicationContext.getMainWindow).setWindowClosingStrategy(captor.capture())
    captor.getValue
  }

  "A BaseApplicationManager" should "create a correct app service manager" in {
    val helper = new ApplicationManagerTestHelper(mockServiceManagers = false)

    helper.checkServiceManager(helper.manager.applicationServiceManager,
      classOf[Application])
  }

  it should "create a correct shutdown listener service manager" in {
    val helper = new ApplicationManagerTestHelper(mockServiceManagers = false)

    helper.checkServiceManager(helper.manager.shutdownListenerManager,
      classOf[ShutdownListener])
  }

  it should "allow adding new shutdown listeners" in {
    val listener = mock[ShutdownListener]
    val helper = new ApplicationManagerTestHelper

    helper.manager addShutdownListener listener
    verify(helper.listenerServiceManager).addService(listener)
  }

  it should "allow removing a shutdown listener" in {
    val listener = mock[ShutdownListener]
    val helper = new ApplicationManagerTestHelper

    helper.manager removeShutdownListener listener
    verify(helper.listenerServiceManager).removeService(listener)
  }

  it should "allow removing an application" in {
    val app = mock[Application]
    val helper = new ApplicationManagerTestHelper

    helper.manager removeApplication app
    verify(helper.appServiceManager).removeService(app)
  }

  it should "add a newly initialized application" in {
    val app = createApplicationMock()
    val helper = new ApplicationManagerTestHelper

    helper applicationAdded app
  }

  it should "init an application with a shutdown listener" in {
    val app = createApplicationMock()
    val helper = new ApplicationManagerTestHelper

    helper applicationAdded app
    val listener = fetchRegisteredShutdownListener(app)
    listener.canShutdown(app) shouldBe false
    helper.manager.shutdownApps should be(List(app))
  }

  it should "register a shutdown listener that ignores a shutdown callback" in {
    val app = createApplicationMock()
    val helper = new ApplicationManagerTestHelper

    helper applicationAdded app
    val listener = fetchRegisteredShutdownListener(app)
    reset(app)
    listener shutdown app
    verifyZeroInteractions(app)
  }

  it should "init an application with a window closing strategy" in {
    val app = createApplicationMock()
    val helper = new ApplicationManagerTestHelper

    helper applicationAdded app
    val strategy = fetchRegisteredWindowClosingStrategy(app)
    val window = app.getApplicationContext.getMainWindow
    strategy.canClose(window) shouldBe false
    helper.manager.closedWindows should be(List(window))
  }

  it should "correctly tear down the application manager" in {
    val helper = new ApplicationManagerTestHelper

    helper.manager.tearDown()
    verify(helper.bus).removeListener(helper.MessageBusRegistrationID)
    verify(helper.appServiceManager).shutdown()
    verify(helper.listenerServiceManager).shutdown()
  }

  it should "call shutdown listeners when triggering a platform shutdown" in {
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
  }

  it should "abort shutdown if a listener vetos" in {
    val helper = new ApplicationManagerTestHelper

    helper.manager.triggerShutdown()
    val captor = ArgumentCaptor.forClass(
      classOf[UIServiceManager.ProcessFunc[ShutdownListener]])
    verify(helper.listenerServiceManager).processServices(captor.capture())

    val l1, l2, l3 = mock[ShutdownListener]
    when(l1.onShutdown()).thenReturn(true)
    when(l2.onShutdown()).thenReturn(false)
    val listeners = List(l1, l2, l3)
    captor.getValue.apply(listeners) shouldBe 'empty
    verify(l3, never()).onShutdown()
  }

  it should "shutdown all applications" in {
    val app = createApplicationMock()
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
    optMsg.get should be(ClientManagementApplication.Shutdown(helper.appContext))
  }

  /**
    * A test helper class managing all dependencies of the manager to be
    * tested.
    *
    * @param mockServiceManagers a flag whether mock service managers should
    *                            be used
    */
  private class ApplicationManagerTestHelper(mockServiceManagers: Boolean = true) {
    /** Registration ID for the message bus. */
    val MessageBusRegistrationID = 20161217

    /** A mock for the manager for application services. */
    val appServiceManager: UIServiceManager[Application] = mock[UIServiceManager[Application]]

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
    def checkServiceManager(sm: UIServiceManager[_], svcCls: Class[_]): Unit = {
      sm.serviceClass should be(svcCls)
      sm.messageBus should be(bus)
    }

    /**
      * Sends the specified message via the message bus to the test manager.
      *
      * @param msg the message
      * @return this test helper
      */
    def sendMessage(msg: Any): ApplicationManagerTestHelper = {
      val optListener = busListeners.find(_.isDefinedAt(msg))
      optListener shouldBe 'defined
      optListener.get.apply(msg)
      this
    }

    /**
      * Simulates adding of an application and verifies that the application
      * manager has been invoked correctly. The manipulation function passed
      * to the manager is returned.
      *
      * @param app the application to be added
      * @return the manipulation function
      */
    def applicationAdded(app: ClientApplication): Application => Application = {
      sendMessage(ClientApplication.ClientApplicationInitialized(app))
      val captor = ArgumentCaptor.forClass(classOf[Option[Application => Application]])
      verify(appServiceManager).addService(eqArg(app), captor.capture())
      val func = captor.getValue.get
      func(app) should be(app)
      func
    }

    /**
      * Creates a mock for the client application context.
      *
      * @return the mock for the context
      */
    private def createApplicationContext():
    ClientApplicationContext = {
      val context = mock[ClientApplicationContext]
      when(context.messageBus).thenReturn(bus)
      when(bus.registerListener(any(classOf[Receive]))).thenReturn(MessageBusRegistrationID)
      context
    }

    /**
      * Creates and initializes the test instance.
      *
      * @return the test instance
      */
    private def createApplicationManager(): ApplicationManagerImpl = {
      val man = new ApplicationManagerImpl(appServiceManager, listenerServiceManager,
        mockServiceManagers)
      man initApplicationContext appContext
      man.setUp()
      man
    }

    /**
      * Obtains the message bus listeners that have been registered during
      * initialization of the test manager. This can then be used to pass
      * messages to the manager. Note that there are multiple listeners, also
      * for the UI managers.
      *
      * @return the message bus listener function
      */
    private def fetchMessageBusListener(): Iterable[Receive] = {
      val captor = ArgumentCaptor.forClass(classOf[Receive])
      verify(bus, atLeastOnce()).registerListener(captor.capture())
      import collection.JavaConverters._
      captor.getAllValues.asScala
    }
  }

  /**
    * A test implementation of an application manager.
    *
    * @param mockAppManager      can be used to override the app manager
    * @param mockListenerManager can be used to override the listener manager
    * @param mockManagers        flag whether managers should be mocked
    */
  private class ApplicationManagerImpl(mockAppManager: UIServiceManager[Application],
                                       mockListenerManager: UIServiceManager[ShutdownListener],
                                       mockManagers: Boolean)
    extends BaseApplicationManager {
    /** Stores a list with applications passed to onApplicationShutdown(). */
    var shutdownApps: List[Application] = List.empty[Application]

    /** Stores a list with windows passed to onWindowClosing(). */
    var closedWindows: List[Window] = List.empty[Window]

    /**
      * Just increase visibility.
      */
    override def triggerShutdown(): Unit = super.triggerShutdown()

    /**
      * @inheritdoc Tracks this invocation.
      */
    override protected def onApplicationShutdown(app: Application): Unit = {
      super.onApplicationShutdown(app)
      shutdownApps = app :: shutdownApps
    }

    /**
      * @inheritdoc Tracks this invocation.
      */
    override protected def onWindowClosing(window: Window): Unit = {
      super.onWindowClosing(window)
      closedWindows = window :: closedWindows
    }

    override private[app] def applicationServiceManager =
      if (mockManagers) mockAppManager else super.applicationServiceManager

    override private[app] def shutdownListenerManager =
      if (mockManagers) mockListenerManager else super.shutdownListenerManager
  }

}
