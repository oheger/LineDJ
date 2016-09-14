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
package de.oliver_heger.linedj

import java.io.File

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.media.MediaManagerActor
import de.oliver_heger.linedj.archive.metadata.MetaDataManagerActor
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetaDataManagerActor
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, GetAvailableMedia}

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * Simple class starting up the server and testing whether media files are scanned.
 *
 * Start with option -Dconfig.resource=server.conf
 */
object Server {
  def main(args: Array[String]): Unit = {
    val userDir = new File(System.getProperty("user.home"))
    val configFile = new File(userDir, ".linedj-server.conf")
    println("Reading configuration from " + configFile)

    val config = ConfigFactory parseFile configFile
    println("Path config: " + config)
    val regularConfig = ConfigFactory.load()
    val completeConfig = ConfigFactory.load(config.withFallback(regularConfig))
    println("Config: " + completeConfig)

    val system = ActorSystem("LineDJ-Server", completeConfig)
    val serverConfig = MediaArchiveConfig(system.settings.config)
    val persistentMetaDataManager = system.actorOf(PersistentMetaDataManagerActor(serverConfig),
      "persistentMetaDataManager")
    val metaDataManager = system.actorOf(MetaDataManagerActor(serverConfig,
      persistentMetaDataManager), "metaDataManager")
    val mediaManager = system.actorOf(MediaManagerActor(serverConfig, metaDataManager),
      "mediaManager")

    // Trigger scanning of media files and meta data extraction
    mediaManager ! MediaManagerActor.ScanMedia(serverConfig.mediaRoots.map(_.rootPath.toString)
      .toSeq)

    implicit val timeout = Timeout(10.seconds)
    val futureMedia = mediaManager ? GetAvailableMedia
    val media = Await.result(futureMedia, 10.seconds).asInstanceOf[AvailableMedia]
    println("Found media: " + media)
  }
}
