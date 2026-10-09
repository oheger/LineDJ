/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.apps.archive.browser

import de.oliver_heger.linedj.platform.app.{AppWithTestPlatform, ApplicationSyncStartup, ApplicationTestSupport, ClientApplication}
import de.oliver_heger.linedj.platform.archiveclient.ArchiveService
import de.oliver_heger.linedj.platform.bus.MessageBusRegistration
import de.oliver_heger.linedj.platform.comm.MessageBusListener
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for [[BrowserApp]].
  */
class BrowserAppSpec extends AnyFlatSpec, Matchers, MockitoSugar, ApplicationTestSupport:
  "A BrowserApp" should "initialize its name correctly" in :
    val app = new BrowserApp

    app.appName should be(BrowserApp.AppName)

  it should "make a bean for the archive service available in the bean context" in :
    val archiveService = mock[ArchiveService]
    val app = new BrowserApp with ApplicationSyncStartup with AppWithTestPlatform

    app.initArchiveService(archiveService)
    activateApp(app)

    queryBean[ArchiveService](app, BrowserApp.BeanArchiveService) should be(archiveService)

  it should "correctly configure the controller" in :
    val app = new BrowserApp with ApplicationSyncStartup with AppWithTestPlatform

    app.initArchiveService(mock)
    activateApp(app)

    queryBean[Controller](app.getMainWindowBeanContext, "mediaController") should not be null

  it should "define a correct message bus registration bean" in :
    val app = new BrowserApp with ApplicationSyncStartup with AppWithTestPlatform

    app.initArchiveService(mock)
    activateApp(app)

    val beanContext = app.getMainWindowBeanContext
    val busReg = queryBean[MessageBusRegistration](beanContext, ClientApplication.BeanMessageBusRegistration)
    val albumSongsLoader = queryBean[MessageBusListener](beanContext, "albumSongsLoader")
    val artistSongsLoader = queryBean[MessageBusListener](beanContext, "artistSongsLoader")
    val controller = queryBean[MessageBusListener](beanContext, "mediaController")
    val selectionChangeListener = queryBean[MessageBusListener](beanContext, "mediaSelectionChangeListener")

    busReg.listeners should contain only(albumSongsLoader, artistSongsLoader, controller, selectionChangeListener)
