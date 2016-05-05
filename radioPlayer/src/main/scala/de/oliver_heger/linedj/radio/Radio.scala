/*
 * Copyright 2015-2016 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.radio

import akka.actor.ActorSystem
import de.oliver_heger.linedj.player.engine.{PlayerConfig, RadioSource}
import de.oliver_heger.linedj.player.engine.facade.RadioPlayer
import de.oliver_heger.linedj.player.engine.mp3.Mp3PlaybackContextFactory

/**
  * Demo class for playing radio streams.
  */
object Radio {
  def main(args: Array[String]): Unit = {
    if (args.length != 1) {
      println("Usage: Radio <streamURL>")
      System.exit(1)
    }

    val system = ActorSystem("lineDJ-radioPlayer")
    val config = PlayerConfig(mediaManagerActor = null, actorCreator = system.actorOf,
      inMemoryBufferSize = 16384, bufferChunkSize = 4096, playbackContextLimit = 8192,
      blockingDispatcherName = Some("blocking-io-dispatcher"))
    val player = RadioPlayer(config)
    val factory = new Mp3PlaybackContextFactory
    player addPlaybackContextFactory factory

    player.startPlayback()
    player switchToSource RadioSource(args.head, Some("mp3"))
  }
}
