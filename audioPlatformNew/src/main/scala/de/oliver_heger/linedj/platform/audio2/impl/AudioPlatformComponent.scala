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

package de.oliver_heger.linedj.platform.audio2.impl

import de.oliver_heger.linedj.platform.app.{ClientContextSupport, PlatformComponent}
import de.oliver_heger.linedj.platform.audio2.AudioPlayerCommands
import de.oliver_heger.linedj.platform.audio2.impl.AudioPlatformComponent.log
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.Actor
import org.osgi.service.component.ComponentContext

import scala.compiletime.uninitialized

object AudioPlatformComponent:
  private val log = LogManager.getLogger(classOf[AudioPlatformComponent])
end AudioPlatformComponent

/**
  * A declarative services component representing the audio platform.
  *
  * This component is started automatically by the declarative services
  * runtime when all dependencies are satisfied. It is responsible for
  * creating and registering controller objects (and corresponding OSGi
  * services) that control the playback of audio based on commands sent to the
  * central message bus. Of course, correct cleanup needs to be done when the
  * component is deactivated.
  */
class AudioPlatformComponent extends PlatformComponent, ClientContextSupport:
  private var playerCommandRegistration: Int = uninitialized

  override def activate(compContext: ComponentContext): Unit =
    super.activate(compContext)

    playerCommandRegistration = clientApplicationContext.messageBus.registerListener(handleAudioPlayerCommand)

  override def deactivate(componentContext: ComponentContext): Unit =
    super.deactivate(componentContext)
    clientApplicationContext.messageBus.removeListener(playerCommandRegistration)

  private def handleAudioPlayerCommand: Actor.Receive =
    case cmd: AudioPlayerCommands =>
      log.info("Received audio player command: {}.", cmd)
