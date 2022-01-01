/*
 * Copyright 2015-2021 The Developers Team.
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
import de.oliver_heger.linedj.platform.app._
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.ext.ConsumerRegistrationProcessor
import de.oliver_heger.linedj.pleditor.ui.config.PlaylistEditorConfig
import de.oliver_heger.linedj.pleditor.ui.playlist.{PlaylistActionEnabler, PlaylistController}
import de.oliver_heger.linedj.pleditor.ui.reorder.ReorderService
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''PlaylistEditorApp''.
  */
class PlaylistEditorAppSpec extends AnyFlatSpec with Matchers with MockitoSugar with
ApplicationTestSupport {
  /**
    * Creates a new test application instance and starts it up. This instance
    * can then be used to test whether initialization was correctly.
    *
    * @return the instance of the application
    */
  private def createApp(): PlaylistEditorAppTestImpl = {
    val reorderService = mock[ReorderService]
    val app = new PlaylistEditorAppTestImpl(reorderService)
    app initReorderService reorderService
    activateApp(app)
  }

  "A PlaylistEditorApp" should "create a bean for the BrowserConfig" in {
    val app = createApp()
    val config = queryBean[PlaylistEditorConfig](app, PlaylistEditorApp.BeanConfig)
    config.userConfiguration should be(app.getUserConfiguration)
  }

  it should "register message bus listeners correctly" in {
    val application = createApp()

    val uiBus = queryBean[MessageBus](application, ClientApplication.BeanMessageBus)
    verify(uiBus, Mockito.atLeast(1)).registerListener(any(classOf[Actor.Receive]))
  }

  it should "define a correct consumer registration bean" in {
    val application = createApp()

    val consumerReg = queryBean[ConsumerRegistrationProcessor](application
      .getMainWindowBeanContext, ClientApplication.BeanConsumerRegistration)
    val remoteCtrl = queryBean[RemoteController](application.getMainWindowBeanContext,
      "remoteController")
    val playlistCtrl = queryBean[PlaylistController](application.getMainWindowBeanContext,
    "playlistController")
    consumerReg.providers should contain only (remoteCtrl, playlistCtrl)
  }

  it should "construct an instance correctly" in {
    val app = new PlaylistEditorApp

    app shouldBe a[ApplicationAsyncStartup]
    app.appName should be("pleditor")
  }

  it should "create a PlaylistActionEnabler bean for the main window" in {
    val app = createApp()

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
  * A test implementation of the main application class that makes sure that
  * builder scripts are executed against the test platform.
  *
  * @param reorderService the ''ReorderService'' mock
  */
private class PlaylistEditorAppTestImpl(val reorderService: ReorderService)
  extends PlaylistEditorApp with ApplicationSyncStartup with AppWithTestPlatform
