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

package de.oliver_heger.linedj.playlist.persistence

import java.nio.file.Path

import akka.actor.{Actor, ActorLogging}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{FileIO, Sink}
import akka.util.ByteString
import de.oliver_heger.linedj.io.stream.StreamSizeRestrictionStage
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.playlist.persistence.PersistentPlaylistParser.PlaylistItem

import scala.concurrent.{ExecutionContext, Future}

object LoadPlaylistActor {
  /** A position that is used if the position file cannot be read. */
  private val DummyPosition = CurrentPlaylistPosition(0, 0, 0)

  /**
    * The central message processed by [[LoadPlaylistActor]].
    *
    * The message identifies the files to be loaded and also contains a
    * reference to the message bus for publishing the result of the load
    * operation.
    *
    * @param playlistPath the path to the file with the actual playlist
    * @param positionPath the path to the file with the current position
    * @param maxFileSize  the maximum size of a file to be loaded
    * @param messageBus   the system message bus
    */
  case class LoadPlaylistData(playlistPath: Path, positionPath: Path, maxFileSize: Int,
                              messageBus: MessageBus)

}

/**
  * An actor class that loads the persistent information about the current
  * playlist.
  *
  * An instance of this actor class is created when the persistent playlist
  * handler components starts up. It processes a message with the paths of the
  * files to be loaded (one file with all songs in the playlist, and another
  * one with the current position). It loads these files if they are present
  * and valid and extracts the data they contain. For missing data defaults are
  * assumed. The resulting playlist information is published as a specific
  * object on the system message bus. From there it is picked up by the
  * handler component which can then trigger additional actions.
  */
class LoadPlaylistActor extends Actor with ActorLogging {

  import LoadPlaylistActor._

  /** The object to materialize streams. */
  implicit private val materializer: ActorMaterializer = ActorMaterializer()

  override def receive: Receive = {
    case LoadPlaylistData(plPath, posPath, maxFileSize, bus) =>
      import context.dispatcher
      val futPlaylistItems = loadPlaylistFile(plPath, maxFileSize)
        .recover {
          case e =>
            log.error(e, s"Error when reading playlist file $plPath!")
            List.empty[PlaylistItem]
        }
      val futPlaylistPos = loadPositionFile(posPath, maxFileSize)
        .recover {
          case e =>
            log.error(e, s"Error when reading position file $posPath!")
            DummyPosition
        }

      val playlist = for {items <- futPlaylistItems
                          pos <- futPlaylistPos
      } yield PersistentPlaylistParser.generateFinalPlaylist(items, pos)
      playlist foreach { pl =>
        bus publish LoadedPlaylist(pl)
        context stop self
      }
  }

  /**
    * Loads a file with a persistent playlist.
    *
    * @param path    the path to the playlist file
    * @param maxSize the maximum file size
    * @return a ''Future'' for the result of the load operation
    */
  private def loadPlaylistFile(path: Path, maxSize: Int): Future[List[PlaylistItem]] = {
    log.info("Loading persistent playlist from {}.", path)
    val source = FileIO.fromPath(path)
    val sink = Sink.fold[List[PlaylistItem], PlaylistItem](List.empty) { (lst, item) =>
      item :: lst
    }
    source.via(new StreamSizeRestrictionStage(maxSize))
      .via(PersistentPlaylistParser.playlistParserStage)
      .runWith(sink)
  }

  /**
    * Loads a file with position information about a playlist.
    *
    * @param path    the path to the position file
    * @param maxSize the maximum file size
    * @param ec      the execution context
    * @return a ''Future'' for the result of the load operation
    */
  private def loadPositionFile(path: Path, maxSize: Int)(implicit ec: ExecutionContext):
  Future[CurrentPlaylistPosition] = {
    log.info("Loading playlist position from {}.", path)
    val source = FileIO.fromPath(path).via(new StreamSizeRestrictionStage(maxSize))
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    source.runWith(sink).map(bs => CurrentPositionParser.parsePosition(bs.utf8String))
  }
}
