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

package de.oliver_heger.linedj.pleditor.ui.reorder

import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.pleditor.spi.PlaylistReorderer

import scala.collection.immutable.Seq
import scala.concurrent.Future

/**
  * A trait defined a playlist reorder service.
  *
  * This trait defines a couple of methods used within the playlist editor
  * application to reorder parts of the current playlist.
  */
trait ReorderService:
  /**
    * Obtains information about the currently available reorder services. This
    * is fetched asynchronously.
    *
    * @return a future for the sequence of available ''PlaylistReorderer''
    *         services and their names
    */
  def loadAvailableReorderServices(): Future[Seq[(PlaylistReorderer, String)]]

  /**
    * Triggers a reorder operation on a part of the current playlist. The
    * operation is done asynchronously, results are published on the system
    * message bus.
    *
    * @param service  the ''PlaylistReorderer'' to be invoked
    * @param songs    the sequence of songs to be ordered
    * @param startIdx the index of the first song in the playlist
    */
  def reorder(service: PlaylistReorderer, songs: Seq[SongData], startIdx: Int): Unit
