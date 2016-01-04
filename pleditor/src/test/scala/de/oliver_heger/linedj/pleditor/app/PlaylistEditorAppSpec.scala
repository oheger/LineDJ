/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.app

import akka.actor.Actor
import de.oliver_heger.linedj.client.app.{ApplicationAsyncStartup, ClientApplication,
ApplicationSyncStartup, ApplicationTestSupport}
import de.oliver_heger.linedj.client.remoting.MessageBus
import de.oliver_heger.linedj.pleditor.config.PlaylistEditorConfig
import de.oliver_heger.linedj.pleditor.playlist.PlaylistActionEnabler
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.window.Window
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Matchers, FlatSpec}

/**
  * Test class for ''PlaylistEditorApp''.
  */
class PlaylistEditorAppSpec extends FlatSpec with Matchers with MockitoSugar with
ApplicationTestSupport {
  /**
    * Creates a new test application instance and starts it up. This instance
    * can then be used to test whether initialization was correctly.
    * @param mockInitUI flag whether initialization of the UI should be mocked
    * @return the instance of the application
    */
  private def createApp(mockInitUI: Boolean = true): PlaylistEditorAppTestImpl = {
    activateApp(new PlaylistEditorAppTestImpl(mockInitUI))
  }

  "A PlaylistEditorApp" should "create a bean for the BrowserConfig" in {
    val app = createApp()
    val config = queryBean[PlaylistEditorConfig](app, PlaylistEditorApp.BeanConfig)
    config.userConfiguration should be(app.getUserConfiguration)
  }

  it should "register message bus listeners correctly" in {
    val application = createApp(mockInitUI = false)

    val uiBus = queryBean[MessageBus](application, ClientApplication.BeanMessageBus)
    verify(uiBus, atLeastOnce()).registerListener(any(classOf[Actor.Receive]))
  }

  it should "construct an instance correctly" in {
    val app = new PlaylistEditorApp

    app shouldBe a[ApplicationAsyncStartup]
    app.configName should be("pleditor_config.xml")
  }

  it should "create a PlaylistActionEnabler bean for the main window" in {
    val app = createApp(mockInitUI = false)

    val enabler = queryBean[PlaylistActionEnabler](app.getMainWindowBeanContext,
      "playlistActionEnabler")
    enabler.manipulatorMap.keySet should contain allOf("plRemoveAction", "plMoveUpAction",
      "plMoveDownAction", "plMoveTopAction", "plMoveBottomAction")
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
private class PlaylistEditorAppTestImpl(mockInitUI: Boolean)
  extends PlaylistEditorApp with ApplicationSyncStartup {
  override def initGUI(appCtx: ApplicationContext): Unit = {
    if (!mockInitUI) {
      super.initGUI(appCtx)
    }
  }

  override def showMainWindow(window: Window): Unit = {
    // Do not show a window here
  }
}
