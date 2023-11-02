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

package de.oliver_heger.linedj.browser.app

import de.oliver_heger.linedj.browser.media.MediaController
import de.oliver_heger.linedj.platform.app.{ApplicationAsyncStartup, ApplicationSyncStartup, ClientApplication, RemoteController}
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.ext.ConsumerRegistrationProcessor
import de.oliver_heger.linedj.test.{AppWithTestPlatform, ApplicationTestSupport}
import org.apache.pekko.actor.Actor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
 * Test class for ''BrowserApp''.
 */
class BrowserAppSpec extends AnyFlatSpec with Matchers with MockitoSugar with ApplicationTestSupport:
  /**
   * Creates a new test application instance and starts it up. This instance
   * can then be used to test whether initialization was correctly.
   * @return the instance of the application
   */
  private def createApp(): BrowserAppTestImpl =
    activateApp(new BrowserAppTestImpl)

  /**
   * Executes a function on an application. It is ensured that the application
   * is shut down afterwards.
   * @param app the application
   * @param f the function to be applied
   * @return the result of the function
   */
  private def withApplication[T](app: BrowserAppTestImpl = createApp())(f: BrowserAppTestImpl =>
    T): T =
    try f(app)
    finally app.shutdown()

  it should "register message bus listeners correctly" in:
    val application = createApp()

    withApplication(activateApp(application)) { app =>
      val uiBus = queryBean[MessageBus](app, ClientApplication.BeanMessageBus)
      verify(uiBus, atLeastOnce()).registerListener(any(classOf[Actor.Receive]))
    }

  it should "define a correct consumer registration bean" in:
    val application = createApp()

    val consumerReg = queryBean[ConsumerRegistrationProcessor](application
      .getMainWindowBeanContext, ClientApplication.BeanConsumerRegistration)
    val remoteCtrl = queryBean[RemoteController](application.getMainWindowBeanContext,
      "remoteController")
    val mediaCtrl = queryBean[MediaController](application.getMainWindowBeanContext,
      "mediaController")
    consumerReg.providers should contain only (remoteCtrl, mediaCtrl)

  it should "construct an instance correctly" in:
    val app = new BrowserApp

    app shouldBe a[ApplicationAsyncStartup]
    app.appName should be("browser")

/**
  * A test implementation of the main application class that can be used to
  * check the UI declaration files.
  */
private class BrowserAppTestImpl extends BrowserApp with ApplicationSyncStartup with AppWithTestPlatform
