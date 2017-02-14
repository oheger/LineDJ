/*
 * Copyright 2015-2017 The Developers Team.
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

import akka.actor.ActorSystem
import de.oliver_heger.linedj.platform.bus.MessageBusRegistration
import de.oliver_heger.linedj.platform.comm.{ActorFactory, MessageBus}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.ext.ConsumerRegistrationProcessor
import net.sf.jguiraffe.di.BeanContext
import net.sf.jguiraffe.gui.app.{Application, ApplicationContext}
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer
import net.sf.jguiraffe.gui.builder.window.{Window, WindowListener}
import net.sf.jguiraffe.gui.platform.javafx.builder.window.StageFactory
import org.apache.commons.configuration.PropertiesConfiguration
import org.mockito.{ArgumentCaptor, Mockito}
import org.mockito.Matchers.{any, anyBoolean}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''ClientApplication''.
  */
class ClientApplicationSpec extends FlatSpec with Matchers with MockitoSugar with
ApplicationTestSupport {
  /**
    * Creates a mock application context. The mock is also prepared to return
    * the application's main window mock and an empty configuration.
    * @return the mock application context
    */
  private def createApplicationContext(): ApplicationContext = {
    val ctx = mock[ApplicationContext]
    when(ctx.getMainWindow).thenReturn(mock[Window])
    when(ctx.getConfiguration).thenReturn(new PropertiesConfiguration)
    ctx
  }

  /**
    * Creates a test application.
    * @param mainWindowBeanContext a bean context for the main window
    * @return the test application
    */
  private def createApp(mainWindowBeanContext: BeanContext = null): ClientApplication = {
    val app = new ClientApplication("testClientApp") with ApplicationSyncStartup {
      override def getMainWindowBeanContext: BeanContext = mainWindowBeanContext
    }
    app initApplicationManager mock[ApplicationManager]
    app
  }

  /**
    * Creates a test application and starts it so that it is correctly
    * initialized.
    * @return the test application
    */
  private def setUpApp(): ClientApplication =
    activateApp(createApp())

  "A ClientApplication" should "use the correct configuration" in {
    val app = setUpApp()

    app.getConfigResourceName should be("testClientApp_config.xml")
  }

  it should "return a correct client context" in {
    val context = mock[ClientApplicationContext]
    val app = createApp()

    app initClientContext context
    app.clientApplicationContext should be(context)
    verifyZeroInteractions(context)
  }

  it should "define a bean for the stage factory" in {
    val app = setUpApp()

    queryBean[StageFactory](app, JavaFxSharedWindowManager.BeanStageFactory) should be
    app.clientApplicationContext.stageFactory
  }

  it should "define a bean for the actor system" in {
    val app = setUpApp()

    queryBean[ActorSystem](app, ClientApplication.BeanActorSystem) should be(app.
      clientApplicationContext.actorSystem)
  }

  it should "define a bean for the actor factory" in {
    val app = setUpApp()

    queryBean[ActorFactory](app, ClientApplication.BeanActorFactory) should be(app.
      clientApplicationContext.actorFactory)
  }

  it should "define a bean for the message bus" in {
    val app = setUpApp()

    queryBean[MessageBus](app, ClientApplication.BeanMessageBus) should be(app.
      clientApplicationContext.messageBus)
  }

  it should "define a bean for the media facade" in {
    val app = setUpApp()

    queryBean[MediaFacade](app, ClientApplication.BeanMediaFacade) should be
    app.clientApplicationContext.mediaFacade
  }

  it should "define a bean for the client application context" in {
    val app = setUpApp()

    queryBean[ClientApplicationContext](app,
      ClientApplication.BeanClientApplicationContext) should be(app.clientApplicationContext)
  }

  it should "correctly initialize the application" in {
    val app = createApp()
    val appContext = createApplicationContext()
    val clientContext = new ClientApplicationContextImpl
    app initClientContext clientContext
    app setApplicationContext appContext

    app.initGUI(appContext)
    verify(appContext).getConfiguration
    verify(app.applicationManager).registerApplication(app)
  }

  it should "initialize some special beans if they are present" in {
    val appContext = createApplicationContext()
    val beanContext = mock[BeanContext]
    addBeans(beanContext,
      Map(ClientApplication.BeanMessageBusRegistration -> mock[MessageBusRegistration],
      ClientApplication.BeanConsumerRegistration -> mock[ConsumerRegistrationProcessor]))
    val app = createApp(mainWindowBeanContext = beanContext)
    app initClientContext new ClientApplicationContextImpl
    app setApplicationContext appContext

    app initGUI appContext
    verify(beanContext).getBean(ClientApplication.BeanMessageBusRegistration)
    verify(beanContext).getBean(ClientApplication.BeanConsumerRegistration)
  }

  it should "not initialize special beans that are not present" in {
    val appContext = createApplicationContext()
    val beanContext = mock[BeanContext]
    when(appContext.getConfiguration).thenReturn(new PropertiesConfiguration)
    val app = createApp(mainWindowBeanContext = beanContext)
    app initClientContext new ClientApplicationContextImpl
    app setApplicationContext appContext

    app initGUI appContext
    verify(beanContext, never()).getBean(ClientApplication.BeanMessageBusRegistration)
    verify(beanContext, never()).getBean(ClientApplication.BeanConsumerRegistration)
  }

  it should "support updating the title of its main window" in {
    val Title = "Changed Window Title"
    val appContext = createApplicationContext()
    val app = createApp()
    app setApplicationContext appContext

    app updateTitle Title
    val io = Mockito.inOrder(appContext.getMainWindow, app.applicationManager)
    io.verify(appContext.getMainWindow).setTitle(Title)
    io.verify(app.applicationManager).applicationTitleUpdated(app, Title)
  }

  it should "not throw if the title of a non-existing window is changed" in {
    val app = createApp()
    app setApplicationContext mock[ApplicationContext]

    app updateTitle "Should now throw"
  }

  /**
    * Notifies the window listener that is registered by the application at
    * its main window that the window has now been opened.
    *
    * @param appCtx the application context
    * @return the mock for the main window
    */
  private def notifyWindowOpened(appCtx: ApplicationContext): Window = {
    val window = appCtx.getMainWindow
    val captor = ArgumentCaptor.forClass(classOf[WindowListener])
    verify(window).addWindowListener(captor.capture())
    captor.getValue windowOpened null
    window
  }

  /**
    * Helper method that tests whether the main window's visible state is
    * correctly updated.
    *
    * @param visible the visible state
    */
  private def checkChangeWindowVisibility(visible: Boolean): Unit = {
    val appContext = createApplicationContext()
    val app = createApp()
    app initClientContext new ClientApplicationContextImpl
    app setApplicationContext appContext

    app.initGUI(appContext)
    val window = notifyWindowOpened(appContext)
    app.showMainWindow(visible)
    verify(window).setVisible(visible)
  }

  it should "allow hiding the main window" in {
    checkChangeWindowVisibility(visible = false)
  }

  it should "allow showing the main window" in {
    checkChangeWindowVisibility(visible = true)
  }

  /**
    * Creates a mock GUI sync bean and installs it in the bean context of the
    * given application context.
    *
    * @param appContext the application context
    * @return the mock ''GUISynchronizer''
    */
  private def prepareSync(appContext: ApplicationContext): GUISynchronizer = {
    val bc = mock[BeanContext]
    val sync = mock[GUISynchronizer]
    doReturn(sync).when(bc).getBean(Application.BEAN_GUI_SYNCHRONIZER)
    when(appContext.getBeanContext).thenReturn(bc)
    sync
  }

  /**
    * Expects a pending sync action and returns it.
    *
    * @param sync the ''GUISynchronizer''
    * @return the action
    */
  private def expectSyncAction(sync: GUISynchronizer): Runnable = {
    val captor = ArgumentCaptor.forClass(classOf[Runnable])
    verify(sync).asyncInvoke(captor.capture())
    captor.getValue
  }

  it should "allow hiding the window before it is opened" in {
    val appContext = createApplicationContext()
    val app = createApp()
    app initClientContext new ClientApplicationContextImpl
    app setApplicationContext appContext
    val sync = prepareSync(appContext)
    app.initGUI(appContext)

    app.showMainWindow(display = false)
    verify(appContext.getMainWindow, never()).setVisible(anyBoolean())
    notifyWindowOpened(appContext)
    verify(appContext.getMainWindow, never()).setVisible(anyBoolean())
    expectSyncAction(sync).run()
    verify(appContext.getMainWindow).setVisible(false)
  }

  it should "hide the application window only once when being opened" in {
    val appContext = createApplicationContext()
    val app = createApp()
    app initClientContext new ClientApplicationContextImpl
    app setApplicationContext appContext
    val sync = prepareSync(appContext)
    app.initGUI(appContext)

    app.showMainWindow(display = false)
    notifyWindowOpened(appContext)
    notifyWindowOpened(appContext)
    verify(sync).asyncInvoke(any(classOf[Runnable]))
  }
}
