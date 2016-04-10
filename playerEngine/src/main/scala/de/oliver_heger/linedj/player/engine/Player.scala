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

package de.oliver_heger.linedj.player.engine

import java.nio.file.{Files, Path, Paths}

import akka.actor.{ActorRef, ActorSystem, Props}
import de.oliver_heger.linedj.media.MediumID
import de.oliver_heger.linedj.player.engine.impl._

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
    val config = PlayerConfig(actorCreator = system.actorOf)
    val mediaManagerActor = fetchMediaManagerActor(system)
    val bufferManager = new BufferFileManager(fetchTemporaryPath(), "buffer", ".tmp")
    val bufferActor = system.actorOf(LocalBufferActor(config, bufferManager), "bufferActor")
    val sourceReaderActor = system.actorOf(Props(classOf[SourceReaderActor], bufferActor),
      "sourceReaderActor")
    val sourceDownloadActor = system.actorOf(SourceDownloadActor(config, mediaManagerActor,
      bufferActor, sourceReaderActor), "sourceDownloadActor")
    val lineWriterActor = system.actorOf(Props[LineWriterActor], "lineWriterActor")
    val playbackActor = system.actorOf(PlaybackActor(config, sourceReaderActor, lineWriterActor),
      "playbackActor")
    playbackActor ! PlaybackActor.AddPlaybackContextFactory(new Mp3PlaybackContextFactory)

    val mediumID = MediumID(args(0), Some(args(1)))

    def playlistInfo(uri: String, index: Int): AudioSourcePlaylistInfo = {
      val source = AudioSourceID(mediumID, uri)
      AudioSourcePlaylistInfo(source, index, 0, 0)
    }

    val playListMessages = args.drop(2).zipWithIndex.map(t => playlistInfo(t._1, t._2))
    playListMessages foreach sourceDownloadActor.!
    sourceDownloadActor ! SourceDownloadActor.PlaylistEnd
    playbackActor ! PlaybackActor.StartPlayback
  }

  /**
    * Determines the directory where to store temporary files. This directory
    * is located in the user's home directory. If it does not exist yet, it is
    * created.
    *
    * @return the path where to store temporary files
    */
  private def fetchTemporaryPath(): Path = {
    val home = Paths get System.getProperty("user.home")
    val tempPath = home.resolve(".lineDJ").resolve("temp")
    Files createDirectories tempPath
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
