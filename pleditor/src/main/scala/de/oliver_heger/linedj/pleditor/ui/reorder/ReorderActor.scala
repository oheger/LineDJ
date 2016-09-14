/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.ui.reorder

import akka.actor.Actor
import de.oliver_heger.linedj.platform.model.SongData
import de.oliver_heger.linedj.pleditor.spi.PlaylistReorderer
import de.oliver_heger.linedj.pleditor.ui.reorder.ReorderActor.{ReorderResponse, ReorderRequest}

object ReorderActor {

  /**
    * A message processed by [[ReorderActor]] that requests a reorder
    * operation. The contained sequence of songs is passed to the wrapped
    * ''PlaylistReorderer'' object.
    * @param songs the sequence of songs to be reordered
    * @param startIndex the first index of a song in the playlist
    */
  case class ReorderRequest(songs: Seq[SongData], startIndex: Int)

  /**
    * A message produced by [[ReorderActor]] representing the result of a
    * reorder operation. Objects of this class are published on the system
    * message bus when a reorder operation was finished successfully.
    * @param reorderedSongs the reordered sequence of songs
    * @param request the request which produced this response
    */
  case class ReorderResponse(reorderedSongs: Seq[SongData], request: ReorderRequest)

}

/**
  * An actor which wraps a [[de.oliver_heger.linedj.pleditor.spi.PlaylistReorderer]]
  * service.
  *
  * This actor implementation can handle a single message which triggers a
  * reorder operation on a sequence of songs. This message is processed by
  * invoking a wrapped ''PlaylistReorderer'' object. The resulting reordered
  * sequence of songs is then sent back to the original sender.
  *
  * For each available ''PlaylistReorderer'' implementation a single instance
  * of this actor class is created. When executing a reorder operation the
  * corresponding actor is called. If this fails - because the reorder service
  * is no longer available -, the actor instance is stopped.
  *
  * @param reorder the wrapped ''PlaylistReorderer''
  */
class ReorderActor(reorder: PlaylistReorderer) extends Actor {
  override def receive: Receive = {
    case request: ReorderRequest =>
      sender ! ReorderResponse(reorder.reorder(request.songs), request)
  }
}
