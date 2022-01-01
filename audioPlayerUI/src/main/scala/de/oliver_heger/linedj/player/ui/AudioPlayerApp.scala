/*
 * Copyright 2015-2022 The Developers Team.
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

import de.oliver_heger.linedj.platform.app.{ApplicationAsyncStartup, ClientApplication}
import net.sf.jguiraffe.gui.app.ApplicationContext

object AudioPlayerApp {
  /** The name of this application. */
  val ApplicationName = "audioPlayer"

  /**
    * Name of a bean in the application context with the current player
    * configuration.
    */
  val BeanPlayerConfig: String = ApplicationName + ".config"
}

/**
  * The main application class for the audio player application.
  */
class AudioPlayerApp extends ClientApplication("audioPlayer") with ApplicationAsyncStartup {
  import AudioPlayerApp._

  /**
    * @inheritdoc This implementation adds some beans defined in the client
    *             application context to this application's ''BeanContext'',
    *             so that they are available everywhere in this application.
    */
  override def createApplicationContext(): ApplicationContext = {
    val appCtx = super.createApplicationContext()
    addBeanDuringApplicationStartup(BeanPlayerConfig,
      AudioPlayerConfig(clientApplicationContext.managementConfiguration))
    appCtx
  }
}
