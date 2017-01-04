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

package de.oliver_heger.linedj.browser.app

import akka.actor.Actor
import de.oliver_heger.linedj.browser.media.MediaController
import de.oliver_heger.linedj.platform.app._
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.ext.ConsumerRegistrationProcessor
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.window.Window
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''BrowserApp''.
 */
class BrowserAppSpec extends FlatSpec with Matchers with MockitoSugar with ApplicationTestSupport {
  /**
   * Creates a new test application instance and starts it up. This instance
   * can then be used to test whether initialization was correctly.
   * @param mockInitUI flag whether initialization of the UI should be mocked
   * @return the instance of the application
   */
  private def createApp(mockInitUI: Boolean = true): BrowserAppTestImpl = {
    activateApp(new BrowserAppTestImpl(mockInitUI))
  }

  /**
   * Executes a function on an application. It is ensured that the application
   * is shut down afterwards.
   * @param app the application
   * @param f the function to be applied
   * @return the result of the function
   */
  private def withApplication[T](app: BrowserAppTestImpl = createApp())(f: BrowserAppTestImpl =>
    T): T = {
    try f(app)
    finally app.shutdown()
  }

  it should "register message bus listeners correctly" in {
    val application = createApp(mockInitUI = false)

    withApplication(activateApp(application)) { app =>
      val uiBus = queryBean[MessageBus](app, ClientApplication.BeanMessageBus)
      verify(uiBus, atLeastOnce()).registerListener(any(classOf[Actor.Receive]))
    }
  }

  it should "define a correct consumer registration bean" in {
    val application = createApp(mockInitUI = false)

    val consumerReg = queryBean[ConsumerRegistrationProcessor](application
      .getMainWindowBeanContext, ClientApplication.BeanConsumerRegistration)
    val remoteCtrl = queryBean[RemoteController](application.getMainWindowBeanContext,
      "remoteController")
    val mediaCtrl = queryBean[MediaController](application.getMainWindowBeanContext,
      "mediaController")
    consumerReg.providers should contain only (remoteCtrl, mediaCtrl)
  }

  it should "construct an instance correctly" in {
    val app = new BrowserApp

    app shouldBe a[ApplicationAsyncStartup]
    app.appName should be("browser")
  }
}

/**
 * A test implementation of the main application class. This class does not
 * show the main window. It can be used to test whether the application has
 * been correctly initialized. Note that initialization of the UI is possible
 * only once; otherwise, JavaFX complains that it is already initialized.
 *
 * @param mockInitUI flag whether initialization of the UI should be mocked
 */
private class BrowserAppTestImpl(mockInitUI: Boolean)
  extends BrowserApp with ApplicationSyncStartup {
  override def initGUI(appCtx: ApplicationContext): Unit = {
    if (!mockInitUI) {
      super.initGUI(appCtx)
    }
  }

  override def showMainWindow(window: Window): Unit = {
    // Do not show a window here
  }
}