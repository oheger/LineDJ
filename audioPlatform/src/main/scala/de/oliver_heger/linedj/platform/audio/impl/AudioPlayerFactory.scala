/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio.impl

import de.oliver_heger.linedj.platform.app.support.ActorManagementComponent
import de.oliver_heger.linedj.player.engine.client.config.ManagingActorCreator
import de.oliver_heger.linedj.player.engine.facade.AudioPlayer
import org.apache.commons.configuration.Configuration
import org.apache.pekko.actor.{ActorRef, ActorSystem}

import scala.concurrent.{ExecutionContext, Future}

/**
  * An internally used helper class that creates audio player objects.
  *
  * @param playerConfigFactory the factory for the player configuration
  */
private class AudioPlayerFactory(private[impl] val playerConfigFactory: PlayerConfigFactory):
  /**
    * Creates a new instance of ''AudioPlayerFactory'' using a default
    * configuration factory.
    *
    * @return the new instance
    */
  def this() = this(new PlayerConfigFactory)

  /**
    * Creates a new audio player instance asynchronously.
    *
    * @param c            the application configuration
    * @param prefix       the prefix for the config settings of the player
    * @param mediaManager the media manager actor
    * @param management   the actor management component instance
    * @return a ''Future'' with the new audio player instance
    */
  def createAudioPlayer(c: Configuration, prefix: String, mediaManager: ActorRef, management: ActorManagementComponent)
                       (implicit system: ActorSystem, ec: ExecutionContext): Future[AudioPlayer] =
    val creator = new ManagingActorCreator(management.clientApplicationContext.actorFactory, management)
    val config = playerConfigFactory.createPlayerConfig(c, prefix, mediaManager, creator)
    AudioPlayer(config)
