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

package de.oliver_heger.linedj.platform.app.hide.impl

import de.oliver_heger.linedj.platform.app.hide._
import de.oliver_heger.linedj.platform.app.{ApplicationManager, ClientApplication, ClientApplicationContextImpl}
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.ConsumerFunction
import net.sf.jguiraffe.gui.builder.window.Window
import org.apache.commons.configuration.{Configuration, HierarchicalConfiguration}
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object WindowHidingApplicationManagerSpec {
  /** A test application name. */
  private val AppName = "testApplication"

  /**
    * Initializes the given configuration with window management data. All
    * specified application names are marked as invisible.
    *
    * @param config        the configuration
    * @param invisibleApps list of invisible application names
    * @return the configuration
    */
  private def initWindowManagementConfig(config: Configuration,
                                         invisibleApps: String*): Configuration = {
    config.addProperty("platform.windowManagement.config", true)
    invisibleApps foreach { n =>
      config.addProperty("platform.windowManagement.apps." + n, false)
    }
    config
  }
}

/**
  * Test class for ''WindowHidingApplicationManager''.
  */
class WindowHidingApplicationManagerSpec extends FlatSpec with Matchers with MockitoSugar {
  import WindowHidingApplicationManagerSpec._

  /**
    * Creates a registration for an application window state consumer with a
    * mock consumer function.
    *
    * @return the registration
    */
  private def createRegistration(): WindowStateConsumerRegistration =
    WindowStateConsumerRegistration(ComponentID(),
      mock[ConsumerFunction[ApplicationWindowState]])

  /**
    * Creates a mock for an application object that has a main window and a
    * user configuration.
    *
    * @param name the name of the application
    * @return the application mock
    */
  private def createApplicationMock(name: String = AppName): ClientApplication = {
    val app = mock[ClientApplication]
    val window = mock[Window]
    when(app.optMainWindow).thenReturn(Some(window))
    when(app.getUserConfiguration).thenReturn(new HierarchicalConfiguration)
    when(app.appName).thenReturn(name)
    app
  }

  /**
    * Creates mock data about applications. A number of application mocks is
    * created, and for each one a test title is generated.
    *
    * @param appCount the number of of mock applications
    * @return the mock application data
    */
  private def createApplicationData(appCount: Int): List[(ClientApplication, String)] =
    (1 to appCount).map(i => (createApplicationMock(AppName + i), "AppTitle" + i)).toList

  "A WindowHidingApplicationManager" should "have an empty initial state" in {
    val reg = createRegistration()
    val manager = new WindowHidingAppManagerTestImpl

    manager sendMessage reg
    verify(reg.callback).apply(ApplicationWindowState(List.empty, Set.empty))
  }

  it should "notify consumers about a new application" in {
    val reg = createRegistration()
    val data = createApplicationData(1)
    val app = data.head._1
    val manager = new WindowHidingAppManagerTestImpl
    manager sendMessage reg

    manager.initAppData(data).sendMessage(ApplicationManager.ApplicationRegistered(app))
    verify(reg.callback).apply(ApplicationWindowState(data, Set(app)))
  }

  it should "notify consumers about a removed application" in {
    val reg = createRegistration()
    val data = createApplicationData(1)
    val app = createApplicationMock()
    val manager = new WindowHidingAppManagerTestImpl
    manager.sendMessage(ApplicationManager.ApplicationRegistered(data.head._1))
      .sendMessage(ApplicationManager.ApplicationRegistered(app))
      .sendMessage(reg)

    manager.initAppData(data).sendMessage(ApplicationManager.ApplicationRemoved(app))
    verify(reg.callback).apply(ApplicationWindowState(data, Set(data.head._1)))
  }

  it should "notify consumers about an updated title" in {
    val reg = createRegistration()
    val data = createApplicationData(4)
    val manager = new WindowHidingAppManagerTestImpl

    manager.sendMessage(reg).initAppData(data)
      .sendMessage(ApplicationManager.ApplicationTitleUpdated(data(1)._1, data(1)._2))
    verify(reg.callback).apply(ApplicationWindowState(data, Set.empty))
  }

  it should "allow removing a consumer" in {
    val reg = createRegistration()
    val manager = new WindowHidingAppManagerTestImpl
    manager.sendMessage(reg)
    reset(reg.callback)

    manager.sendMessage(WindowStateConsumerUnregistration(reg.id))
      .addApplications(createApplicationData(2))
    verifyZeroInteractions(reg.callback)
  }

  it should "process a message to hide an application window" in {
    val reg = createRegistration()
    val data = createApplicationData(2)
    val app = data(1)._1
    val manager = new WindowHidingAppManagerTestImpl

    manager.addApplications(data).sendMessage(reg)
      .sendMessage(HideApplicationWindow(app))
    verify(app).showMainWindow(false)
    verify(reg.callback).apply(ApplicationWindowState(data, Set(data.head._1)))
  }

  it should "only hide an application window if it is visible" in {
    val reg = createRegistration()
    val data = createApplicationData(4)
    val app = createApplicationMock()
    val manager = new WindowHidingAppManagerTestImpl

    manager.addApplications(data).sendMessage(reg)
      .sendMessage(HideApplicationWindow(app))
    verify(reg.callback).apply(ApplicationWindowState(data, data.map(_._1).toSet))
    verify(app, never()).showMainWindow(false)
  }

  it should "process a message to show an application window" in {
    val reg = createRegistration()
    val data = createApplicationData(2)
    val manager = new WindowHidingAppManagerTestImpl
    manager.addApplications(data).sendMessage(HideApplicationWindow(data.head._1))
      .sendMessage(HideApplicationWindow(data(1)._1)).sendMessage(reg)

    manager.sendMessage(ShowApplicationWindow(data.head._1))
    verify(data.head._1).showMainWindow(true)
    verify(reg.callback).apply(ApplicationWindowState(data, Set(data.head._1)))
  }

  it should "only show an application window that is not already visible" in {
    val reg = createRegistration()
    val data = createApplicationData(1)
    val app = data.head._1
    val manager = new WindowHidingAppManagerTestImpl

    manager.addApplications(data).sendMessage(reg)
      .sendMessage(ShowApplicationWindow(app))
    verify(reg.callback).apply(ApplicationWindowState(data, Set(app)))
    verify(app, never()).showMainWindow(true)
  }

  it should "only show the window of a known application" in {
    val reg = createRegistration()
    val data = createApplicationData(4)
    val app = createApplicationMock()
    val manager = new WindowHidingAppManagerTestImpl

    manager.addApplications(data).sendMessage(reg)
      .sendMessage(ShowApplicationWindow(app))
    verify(reg.callback, never()).apply(ApplicationWindowState(data,
      data.map(_._1).toSet + app))
    verify(app, never()).showMainWindow(true)
  }

  it should "hide an application window when the application is shutdown" in {
    val app = createApplicationMock()
    val manager = new WindowHidingAppManagerTestImpl

    manager onApplicationShutdown app
    manager.publishedMessages should contain only HideApplicationWindow(app)
  }

  it should "hide an application window when it is closed" in {
    val data = createApplicationData(2)
    val app = data.head._1
    val manager = new WindowHidingAppManagerTestImpl
    manager.initAppData(data)

    manager onWindowClosing app.optMainWindow.get
    manager.publishedMessages should contain only HideApplicationWindow(app)
  }

  it should "ignore a window closing event for an unknown window" in {
    val manager = new WindowHidingAppManagerTestImpl
    manager.initAppData(createApplicationData(4))

    manager onWindowClosing mock[Window]
    manager.publishedMessages should have size 0
  }

  it should "handle apps without a window when processing a window closed event" in {
    val Count = 8
    val data = createApplicationData(Count)
    when(data(1)._1.optMainWindow).thenReturn(None)
    val app = data.last._1
    val manager = new WindowHidingAppManagerTestImpl
    manager.initAppData(data)

    manager onWindowClosing app.optMainWindow.get
    manager.publishedMessages should contain only HideApplicationWindow(app)
  }

  it should "process an ExitPlatform message" in {
    val manager = new WindowHidingAppManagerTestImpl

    manager.sendMessage(ExitPlatform(manager))
    manager.shutdownTriggerCount should be(1)
  }

  it should "ignore an invalid ExitPlatform message" in {
    val manager = new WindowHidingAppManagerTestImpl

    manager.sendMessage(ExitPlatform(null))
    manager.shutdownTriggerCount should be(0)
  }

  it should "obtain a window configuration from the client app context" in {
    val manager = new WindowHidingAppManagerTestImpl
    initWindowManagementConfig(manager.clientApplicationContext
      .managementConfiguration, AppName)
    manager initApplicationContext manager.clientApplicationContext
    val app = createApplicationMock()
    val reg = createRegistration()

    manager.sendMessage(ApplicationManager.ApplicationRegistered(app))
      .sendMessage(reg)
    verify(app).showMainWindow(false)
    val captor = ArgumentCaptor.forClass(classOf[ApplicationWindowState])
    verify(reg.callback).apply(captor.capture())
    captor.getValue.visibleApps shouldBe 'empty
  }

  it should "obtain a window configuration from a registered application" in {
    val data = createApplicationData(2)
    val AppName2 = AppName + "_2"
    val app = data.head._1
    when(app.appName).thenReturn(AppName2)
    initWindowManagementConfig(app.getUserConfiguration, AppName2)
    val reg = createRegistration()
    val manager = new WindowHidingAppManagerTestImpl

    manager.addApplications(data).sendMessage(reg)
    verify(app).showMainWindow(false)
    verify(data(1)._1, never()).showMainWindow(false)
    verify(reg.callback).apply(ApplicationWindowState(data, Set(data(1)._1)))
  }

  it should "adapt window visible states when receiving a new configuration" in {
    val data = createApplicationData(3)
    val app = createApplicationMock()
    initWindowManagementConfig(app.getUserConfiguration, AppName + "1",
      AppName + "3", AppName)
    val reg = createRegistration()
    val manager = new WindowHidingAppManagerTestImpl

    manager.addApplications(data)
      .sendMessage(ApplicationManager.ApplicationRegistered(app))
      .sendMessage(reg)
    verify(app).showMainWindow(false)
    verify(data.head._1).showMainWindow(false)
    verify(data(2)._1).showMainWindow(false)
    verify(reg.callback).apply(ApplicationWindowState(data, Set(data(1)._1)))
  }

  it should "not hide a main application" in {
    val app = createApplicationMock()
    val config = initWindowManagementConfig(app.getUserConfiguration, AppName)
    config.addProperty("platform.windowManagement.main.name", AppName)
    val manager = new WindowHidingAppManagerTestImpl

    manager.sendMessage(ApplicationManager.ApplicationRegistered(app))
    verify(app, never()).showMainWindow(false)
  }

  it should "update the window configuration if a window is shown" in {
    val data = createApplicationData(2)
    val app = data.head._1
    val config = initWindowManagementConfig(app.getUserConfiguration, app.appName)
    val manager = new WindowHidingAppManagerTestImpl

    manager.addApplications(data)
      .sendMessage(ShowApplicationWindow(app))
    config.containsKey("platform.windowManagement.apps." + app.appName) shouldBe false
  }

  it should "update the window configuration if a window is hidden" in {
    val data = createApplicationData(2)
    val app = data.head._1
    val config = initWindowManagementConfig(app.getUserConfiguration)
    val manager = new WindowHidingAppManagerTestImpl

    manager.addApplications(data)
      .sendMessage(HideApplicationWindow(app))
    config.getBoolean("platform.windowManagement.apps." + app.appName) shouldBe false
  }

  it should "correctly activate the component" in {
    val manager = new WindowHidingAppManagerTestImpl

    manager activate null
    manager.setupCount should be(1)
  }

  it should "correctly deactivate the component" in {
    val manager = new WindowHidingAppManagerTestImpl

    manager deactivate null
    manager.tearDownCount should be(1)
  }
}

/**
  * A test implementation of the application manager allowing advanced mocking
  * facilities.
  */
private class WindowHidingAppManagerTestImpl extends WindowHidingApplicationManager {
  /** Stores mock application data. */
  private var appData: Iterable[(ClientApplication, String)] = List.empty

  val clientApplicationContext: ClientApplicationContextImpl = createClientAppContext()

  /** A list with messages published on the message bus. */
  private var publishedMessagesList = List.empty[Any]

  /** A counter for invocations of the shutdown trigger. */
  var shutdownTriggerCount: Int = 0

  /** A counter for invocations of setup(). */
  var setupCount: Int = 0

  /** A counter for invocations of tearDown(). */
  var tearDownCount: Int = 0

  /**
    * Initializes the mock application data of this instance.
    *
    * @param data the mock data
    * @return this instance
    */
  def initAppData(data: Iterable[(ClientApplication, String)]):
  WindowHidingAppManagerTestImpl = {
    appData = data
    this
  }

  /**
    * Initializes the mock application data of this instance and registers all
    * applications.
    *
    * @param data the mock data
    * @return this instance
    */
  def addApplications(data: Iterable[(ClientApplication, String)]):
  WindowHidingAppManagerTestImpl = {
    data foreach (d => sendMessage(ApplicationManager.ApplicationRegistered(d._1)))
    initAppData(data)
  }

  /**
    * Sends the specified message to this application manager.
    *
    * @param msg the message to be sent
    * @return this instance
    */
  def sendMessage(msg: Any): WindowHidingAppManagerTestImpl = {
    onMessage(msg)
    this
  }

  /**
    * Returns a list with messages published on the message bus.
    *
    * @return the list with published messages
    */
  def publishedMessages: List[Any] = publishedMessagesList.reverse

  /**
    * Returns mock application data.
    */
  override def getApplicationsWithTitles: Iterable[(ClientApplication, String)] =
    appData

  /**
    * Returns mock application data.
    */
  override def getApplications: Iterable[ClientApplication] =
    appData map (_._1)

  /**
    * Records this invocation.
    */
  override protected def triggerShutdown(): Unit = {
    shutdownTriggerCount += 1
  }

  /**
    * Records this invocation.
    */
  override def setUp(): Unit = {
    setupCount += 1
  }

  /**
    * Records this invocation.
    */
  override def tearDown(): Unit = {
    tearDownCount += 1
  }

  /**
    * Increases visibility.
    */
  override def onApplicationShutdown(app: ClientApplication): Unit = super
    .onApplicationShutdown(app)

  /**
    * Increases visibility.
    */
  override def onWindowClosing(window: Window): Unit =
    super.onWindowClosing(window)

  /**
    * Creates the client application context and a mock for the message bus.
    *
    * @return the client application context
    */
  private def createClientAppContext(): ClientApplicationContextImpl = {
    val ctx = new ClientApplicationContextImpl
    doAnswer(new Answer[AnyRef] {
      override def answer(invocation: InvocationOnMock): AnyRef = {
        publishedMessagesList = invocation.getArguments.head :: publishedMessagesList
        null
      }
    }).when(ctx.messageBus).publish(any())
    initApplicationContext(ctx)
    ctx
  }
}
