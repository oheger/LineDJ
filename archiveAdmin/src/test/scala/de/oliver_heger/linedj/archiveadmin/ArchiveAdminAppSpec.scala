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

package de.oliver_heger.linedj.archiveadmin

import de.oliver_heger.linedj.platform.app._
import de.oliver_heger.linedj.platform.mediaifc.ext.ConsumerRegistrationProcessor
import net.sf.jguiraffe.gui.app.ApplicationContext
import net.sf.jguiraffe.gui.builder.window.Window
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''ArchiveAdminApp''.
  */
class ArchiveAdminAppSpec extends FlatSpec with Matchers with ApplicationTestSupport {
  "An ArchiveAdminApp" should "construct an instance correctly" in {
    val app = new ArchiveAdminApp

    app shouldBe a[ApplicationAsyncStartup]
    app.appName should be("archiveAdmin")
  }

  it should "define a correct consumer registration bean" in {
    val application = activateApp(new ArchiveAdminAppTestImpl(mockInitUI = false))

    val consumerReg = queryBean[ConsumerRegistrationProcessor](application
      .getMainWindowBeanContext, ClientApplication.BeanConsumerRegistration)
    val controller = queryBean[ArchiveAdminController](application.getMainWindowBeanContext,
      "adminController")
    consumerReg.providers should contain only controller
  }

  /**
    * A test application implementation that starts up synchronously.
    *
    * @param mockInitUI flag whether the UI should be mocked
    */
  private class ArchiveAdminAppTestImpl(mockInitUI: Boolean)
    extends ArchiveAdminApp with ApplicationSyncStartup {
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
