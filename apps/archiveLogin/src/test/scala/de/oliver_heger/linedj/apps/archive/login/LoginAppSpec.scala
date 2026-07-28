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

package de.oliver_heger.linedj.apps.archive.login

import de.oliver_heger.linedj.platform.app.{AppWithTestPlatform, ApplicationSyncStartup, ApplicationTestSupport}
import de.oliver_heger.linedj.platform.archiveclient.{ArchiveService, LoginService}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for [[LoginApp]].
  */
class LoginAppSpec extends AnyFlatSpec, Matchers, MockitoSugar, ApplicationTestSupport:
  "A LoginApp" should "initialize its name correctly" in :
    val app = new LoginApp

    app.appName should be("login")

  it should "make beans for the services available in the bean context" in :
    val archiveService = mock[ArchiveService]
    val loginService = mock[LoginService]
    val app = new LoginApp with ApplicationSyncStartup with AppWithTestPlatform

    app.initArchiveService(archiveService)
    app.initLoginService(loginService)
    activateApp(app)

    queryBean[ArchiveService](app, LoginApp.BeanArchiveService) should be(archiveService)
    queryBean[LoginService](app, LoginApp.BeanLoginService) should be(loginService)
