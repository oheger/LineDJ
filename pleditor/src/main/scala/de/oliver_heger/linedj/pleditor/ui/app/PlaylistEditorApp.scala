/*
 * Copyright 2015-2020 The Developers Team.
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

import de.oliver_heger.linedj.platform.app.{ApplicationAsyncStartup, ClientApplication}
import de.oliver_heger.linedj.pleditor.ui.reorder.ReorderService
import net.sf.jguiraffe.gui.app.ApplicationContext

object PlaylistEditorApp {
  /** The bean name for the configuration bean. */
  val BeanConfig = "pleditorApp_Configuration"

  /** The bean name for the reorder service. */
  val BeanReorderService = "pleditor_reorderService"
}

/**
  * The main application class of the LineDJ Playlist Editor application.
  */
class PlaylistEditorApp extends ClientApplication("pleditor") with
ApplicationAsyncStartup {
  import PlaylistEditorApp._

  /** Stores the reference to the reorder service. */
  private var reorderService: ReorderService = _

  /**
    * Initializes the reference to the ''ReorderService''. This method is
    * called by the declarative services runtime.
    *
    * @param service the ''ReorderService''
    */
  def initReorderService(service: ReorderService): Unit = {
    reorderService = service
  }

  /**
    * @inheritdoc This implementation adds some specific beans for the
    *             playlist editor application.
    */
  override def createApplicationContext(): ApplicationContext = {
    val context = super.createApplicationContext()
    addBeanDuringApplicationStartup(BeanReorderService, reorderService)
    context
  }
}
