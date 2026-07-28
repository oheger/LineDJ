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

import de.oliver_heger.linedj.platform.app.{ApplicationAsyncStartup, ClientApplication}
import de.oliver_heger.linedj.platform.archiveclient.{ArchiveService, LoginService}
import net.sf.jguiraffe.gui.app.ApplicationContext

import java.util.concurrent.atomic.AtomicReference

object LoginApp:
  /**
    * The name under which the archive service is stored in the bean context.
    */
  final val BeanArchiveService = "archiveService"

  /**
    * The name under which the login service is made available in the bean
    * context.
    */
  final val BeanLoginService = "loginService"
end LoginApp

/**
  * An application class for handling the login into cloud archives managed by
  * an archive server.
  *
  * This application becomes active when the platform has discovered an archive
  * server that supports credentials management. The application's main window
  * then shows the current state of the cloud archives and lists the
  * credentials to be provided by the user. The main functionality is
  * implemented by other components. This application class just manages the
  * OSGi service dependencies and makes them available in the bean context.
  */
class LoginApp extends ClientApplication("login"), ApplicationAsyncStartup:

  import LoginApp.*

  /**
    * Stores the reference to the [[ArchiveService]]. This reference is set
    * from the OSGi thread and later accessed from other threads.
    */
  private val refArchiveService = new AtomicReference[ArchiveService]

  /**
    * Stores the reference to the [[LoginService]]. This reference is set
    * from the OSGi thread and later accessed from other threads.
    */
  private val refLoginService = new AtomicReference[LoginService]

  /**
    * Initializes the reference to the [[ArchiveService]]. This function is
    * called by the OSGi component registry.
    *
    * @param archiveService the [[ArchiveService]]
    */
  def initArchiveService(archiveService: ArchiveService): Unit =
    refArchiveService.set(archiveService)

  /**
    * Initializes the reference to the [[LoginService]]. This function is
    * called by the OSGi component registry.
    *
    * @param loginService the [[LoginService]]
    */
  def initLoginService(loginService: LoginService): Unit =
    refLoginService.set(loginService)

  /**
    * @inheritdoc This implementation adds beans for the referenced OSGi
    *             services to this application's ''BeanContext'', so that they
    *             can be injected into other components.
    */
  override def createApplicationContext(): ApplicationContext =
    val context = super.createApplicationContext()

    addBeanDuringApplicationStartup(BeanArchiveService, refArchiveService.get())
    addBeanDuringApplicationStartup(BeanLoginService, refLoginService.get())

    context
