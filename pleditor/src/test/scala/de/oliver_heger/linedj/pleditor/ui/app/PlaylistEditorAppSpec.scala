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

package de.oliver_heger.linedj.pleditor.ui.app

import akka.actor.Actor
import de.oliver_heger.linedj.platform.app.{ApplicationAsyncStartup, ApplicationSyncStartup, ApplicationTestSupport, ClientApplication}
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.pleditor.ui.config.PlaylistEditorConfig
import de.oliver_heger.linedj.pleditor.ui.playlist.PlaylistActionEnabler
import de.oliver_heger.linedj.pleditor.ui.reorder.ReorderService
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.window.Window
import org.mockito.Matchers._
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''PlaylistEditorApp''.
  */
class PlaylistEditorAppSpec extends FlatSpec with Matchers with MockitoSugar with
ApplicationTestSupport {
  /**
    * Creates a new test application instance and starts it up. This instance
    * can then be used to test whether initialization was correctly.
    *
    * @param mockInitUI flag whether initialization of the UI should be mocked
    * @return the instance of the application
    */
  private def createApp(mockInitUI: Boolean = true): PlaylistEditorAppTestImpl = {
    val reorderService = mock[ReorderService]
    val app = new PlaylistEditorAppTestImpl(mockInitUI, reorderService)
    app initReorderService reorderService
    activateApp(app)
  }

  "A PlaylistEditorApp" should "create a bean for the BrowserConfig" in {
    val app = createApp()
    val config = queryBean[PlaylistEditorConfig](app, PlaylistEditorApp.BeanConfig)
    config.userConfiguration should be(app.getUserConfiguration)
  }

  it should "register message bus listeners correctly" in {
    val application = createApp(mockInitUI = false)

    val uiBus = queryBean[MessageBus](application, ClientApplication.BeanMessageBus)
    verify(uiBus, Mockito.atLeast(3)).registerListener(any(classOf[Actor.Receive]))
  }

  it should "construct an instance correctly" in {
    val app = new PlaylistEditorApp

    app shouldBe a[ApplicationAsyncStartup]
    app.appName should be("pleditor")
  }

  it should "create a PlaylistActionEnabler bean for the main window" in {
    val app = createApp(mockInitUI = false)

    val enabler = queryBean[PlaylistActionEnabler](app.getMainWindowBeanContext,
      "playlistActionEnabler")
    enabler.manipulatorMap.keySet should contain allOf("plRemoveAction", "plMoveUpAction",
      "plMoveDownAction", "plMoveTopAction", "plMoveBottomAction", "plReorderAction")
  }

  it should "create a bean for the reorder service" in {
    val app = createApp()

    queryBean[ReorderService](app, "pleditor_reorderService") should be(app.reorderService)
  }
}

/**
  * A test implementation of the main application class. This class does not
  * show the main window. It can be used to test whether the application has
  * been correctly initialized. Note that initialization of the UI is possible
  * only once; otherwise, JavaFX complains that it is already initialized.
  *
  * @param mockInitUI flag whether initialization of the UI should be mocked
  * @param reorderService the ''ReorderService'' mock
  */
private class PlaylistEditorAppTestImpl(mockInitUI: Boolean, val reorderService: ReorderService)
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
