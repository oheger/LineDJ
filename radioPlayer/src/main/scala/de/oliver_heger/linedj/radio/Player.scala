/*
 * Copyright 2015-2020 The Developers Team.
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

import akka.actor.{ActorRef, ActorSystem}
import de.oliver_heger.linedj.player.engine.facade.AudioPlayer
import de.oliver_heger.linedj.player.engine.{AudioSourcePlaylistInfo, PlayerConfig}
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Main class for starting the MP3 player application.
  */
object Player {
  def main(args: Array[String]): Unit = {
    if (args.length < 3) {
      println("Player <mediumURI> <mediumDescPath> <songURIs> ...")
      System.exit(1)
    }

    val system = ActorSystem("lineDJ-Player")
    val mediaManagerActor = fetchMediaManagerActor(system)
    val config = PlayerConfig(actorCreator = system.actorOf, mediaManagerActor = mediaManagerActor,
      blockingDispatcherName = Some("blocking-io-dispatcher"))
    val player = AudioPlayer(config)
//    player addPlaybackContextFactory new Mp3PlaybackContextFactory

    val mediumID = MediumID(args(0), Some(args(1)))

    def playlistInfo(uri: String): AudioSourcePlaylistInfo = {
      val source = MediaFileID(mediumID, uri)
      AudioSourcePlaylistInfo(source, 0, 0)
    }

    val playListSources = args.drop(2) map playlistInfo
    playListSources foreach player.addToPlaylist
    player.closePlaylist()
    player.startPlayback()
  }

  /**
    * Obtains the reference to the media manager actor from the remote actor
    * system.
    *
    * @return the media manager actor
    */
  private def fetchMediaManagerActor(system: ActorSystem): ActorRef = {
    implicit val timeout = 10.seconds
    val selection = system.actorSelection("akka.tcp://LineDJ-Server@127.0.0" +
      ".1:2552/user/mediaManager")
    val futureRef = selection.resolveOne(timeout)
    Await.result(futureRef, timeout)
  }
}
