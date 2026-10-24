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

package de.oliver_heger.linedj.platform.audio.playlist.persistence

import de.oliver_heger.linedj.io.stream.ListSeparatorStage
import de.oliver_heger.linedj.platform.audio.playlist.persistence.PlaylistSaveService.WriteOperation
import de.oliver_heger.linedj.platform.audio2.playlist.{Playlist, PlaylistService}
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{FileIO, Source}
import org.apache.pekko.util.ByteString
import spray.json.*

import java.nio.file.Path
import scala.concurrent.Future

object PlaylistSaveService:
  /**
    * A data class to represent an operation to write some data to disk. The
    * [[PlaylistSaveService]] handles objects of this type to persist arbitrary
    * data.
    *
    * @param target the target file where to store the data
    * @param data   a [[Source]] with the data to write
    */
  final case class WriteOperation(target: Path,
                                  data: Source[ByteString, Any])

  /**
    * A factory trait for creating new service instances.
    */
  trait Factory:
    /**
      * Returns a new [[PlaylistSaveService]] instance for the provided 
      * parameters.
      *
      * @param playlistService the service to manage playlists
      * @param system          the actor system
      * @return the new service instance
      */
    def apply(playlistService: PlaylistService[Playlist, String])(using system: ActorSystem): PlaylistSaveService
  end Factory

  /** A default [[Factory]] object for creating new service instances. */
  final val create: Factory = new Factory:
    override def apply(playlistService: PlaylistService[Playlist, String])
                      (using system: ActorSystem): PlaylistSaveService =
      new PlaylistSaveService:
        override def createPlaylistSource(playlist: Playlist): Source[ByteString, Any] =
          val fileIDSource = Source(playlistService.toSongList(playlist))
          val jsonStage = ListSeparatorStage.jsonStage[PlaylistModel.PlaylistItem]
          fileIDSource.map(PlaylistModel.PlaylistItem.apply).via(jsonStage)

        override def createPositionSource(position: PlaylistModel.CurrentPlaylistPosition): Source[ByteString, Any] =
          val posJson = position.toJson.prettyPrint
          Source.single(ByteString(posJson))

        override def store(operation: WriteOperation): Future[Done] =
          val sink = FileIO.toPath(operation.target)
          operation.data.runWith(sink).map(_ => Done)(using system.dispatcher)
end PlaylistSaveService

/**
  * A service interface that offers multiple operations related to persisting
  * playlist information.
  *
  * The service can generate the sources for persisting playlist information.
  * It also has a method to write such a source to a persistent storage. This
  * separation between source generation and source writing reflects the state
  * management done in the component that listens for changes on the audio 
  * player state and handles relevant changes.
  */
trait PlaylistSaveService:
  /**
    * Returns a [[Source]] for the data that corresponds to the given playlist.
    *
    * @param playlist the [[Playlist]]
    * @return a [[Source]] for the data in this playlist
    */
  def createPlaylistSource(playlist: Playlist): Source[ByteString, Any]

  /**
    * Returns a [[Source]] for the data that corresponds to the given object 
    * with position information for a playlist.
    *
    * @param position the object with position information
    * @return a [[Source]] for this position data
    */
  def createPositionSource(position: PlaylistModel.CurrentPlaylistPosition): Source[ByteString, Any]

  /**
    * Handles a [[WriteOperation]] asynchronously.
    *
    * @param operation the operation to handle
    * @return a [[Future]] with the result of the operation
    */
  def store(operation: WriteOperation): Future[Done]
