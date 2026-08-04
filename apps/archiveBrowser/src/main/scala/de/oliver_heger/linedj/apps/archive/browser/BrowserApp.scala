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

import de.oliver_heger.linedj.platform.app.{ApplicationAsyncStartup, ClientApplication}
import de.oliver_heger.linedj.platform.archiveclient.ArchiveService
import net.sf.jguiraffe.gui.app.ApplicationContext

import java.util.concurrent.atomic.AtomicReference

object BrowserApp:
  /** The name of this application. */
  final val AppName = "archiveBrowser"

  /**
    * The name under which the archive service is stored in the bean context.
    */
  final val BeanArchiveService = "archiveService"
end BrowserApp

/**
  * The main application class of the Media Browser application.
  *
  * This application becomes active when the platform is connected to an
  * archive server. It provides multiple views to browse through the media and
  * songs contained in the archive. Songs can be selected for creating
  * playlists.
  */
class BrowserApp extends ClientApplication(BrowserApp.AppName), ApplicationAsyncStartup:

  import BrowserApp.*

  /**
    * Stores the reference to the [[ArchiveService]]. This reference is set
    * from the OSGi thread and later accessed from other threads.
    */
  private val refArchiveService = new AtomicReference[ArchiveService]

  /**
    * Initializes the reference to the [[ArchiveService]]. This function is
    * called by the OSGi component registry.
    *
    * @param archiveService the [[ArchiveService]]
    */
  def initArchiveService(archiveService: ArchiveService): Unit =
    refArchiveService.set(archiveService)

  /**
    * @inheritdoc This implementation adds a bean for the referenced archive
    *             service to this application's _BeanContext_, so that it
    *             can be injected into other components.
    */
  override def createApplicationContext(): ApplicationContext =
    val context = super.createApplicationContext()

    addBeanDuringApplicationStartup(BeanArchiveService, refArchiveService.get())

    context
