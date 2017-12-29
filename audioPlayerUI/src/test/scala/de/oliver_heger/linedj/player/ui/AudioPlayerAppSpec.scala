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

package de.oliver_heger.linedj.player.ui

import de.oliver_heger.linedj.platform.app.{ApplicationAsyncStartup, ApplicationSyncStartup,
  ApplicationTestSupport, ClientApplication}
import de.oliver_heger.linedj.platform.bus.MessageBusRegistration
import de.oliver_heger.linedj.platform.mediaifc.ext.ConsumerRegistrationProcessor
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.window.Window
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''AudioPlayerApp''.
  */
class AudioPlayerAppSpec extends FlatSpec with Matchers with ApplicationTestSupport {
  /**
    * Obtains the bean for the UI controller from the current application
    * context.
    *
    * @param application the application
    * @return the UI controller bean
    */
  private def queryController(application: AudioPlayerAppTestImpl): UIController =
    queryBean[UIController](application.getMainWindowBeanContext,
      "uiController")

  "An AudioPlayerApp" should "construct an instance correctly" in {
    val app = new AudioPlayerApp

    app shouldBe a[ApplicationAsyncStartup]
    app.appName should be("audioPlayer")
  }

  it should "define a correct consumer registration bean" in {
    val application = activateApp(new AudioPlayerAppTestImpl(mockInitUI = false))

    val consumerReg = queryBean[ConsumerRegistrationProcessor](application
      .getMainWindowBeanContext, ClientApplication.BeanConsumerRegistration)
    val controller = queryController(application)
    consumerReg.providers should contain only controller
  }

  it should "define a message bus registration bean" in {
    val application = activateApp(new AudioPlayerAppTestImpl(mockInitUI = false))

    val busReg = queryBean[MessageBusRegistration](application.getMainWindowBeanContext,
      ClientApplication.BeanMessageBusRegistration)
    val controller = queryController(application)
    busReg.listeners should contain only controller
  }

  it should "disable all player actions on startup" in {
    val application = activateApp(new AudioPlayerAppTestImpl(mockInitUI = false))

    val actionStore = queryBean[ActionStore](application.getMainWindowBeanContext, "ACTION_STORE")
    import collection.JavaConverters._
    val playerActions = actionStore.getActions(
      actionStore.getActionNamesForGroup(UIController.PlayerActionGroup)).asScala
    playerActions.size should be > 0
    playerActions.forall(!_.isEnabled) shouldBe true
  }

  /**
    * A test application implementation that starts up synchronously.
    *
    * @param mockInitUI flag whether the UI should be mocked
    */
  private class AudioPlayerAppTestImpl(mockInitUI: Boolean)
    extends AudioPlayerApp with ApplicationSyncStartup {
    override def initGUI(appCtx: ApplicationContext): Unit = {
      if (!mockInitUI) {
        super.initGUI(appCtx)
      }
    }

    override def showMainWindow(window: Window): Unit = {
      // Do not show a window here
    }
  }

}
