/*
 * Copyright 2015-2022 The Developers Team.
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

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.comm.MessageBusListener
import net.sf.jguiraffe.gui.builder.components.model.TableHandler

/**
  * A class which handles the result of a reorder operation.
  *
  * Reorder operations are done asynchronously. Their result - an ordered list
  * of songs - is then published on the system message bus. This class
  * registers itself as listener on the message bus and reacts on such
  * results. When invoked it updates the model of the playlist table
  * accordingly.
  *
  * @param tableHandler the handler for the playlist table
  */
class ReorderResultHandler(tableHandler: TableHandler) extends MessageBusListener {
  override def receive: Receive = {
    case result: ReorderActor.ReorderResponse =>
      val model = tableHandler.getModel
      val startIndex = result.request.startIndex
      val updateIndices = result.reorderedSongs.indices map (_ + startIndex)
      result.reorderedSongs.zip(updateIndices).foreach {
        e => model.set(e._2, e._1)
      }
      tableHandler.rowsUpdated(startIndex, startIndex + result.reorderedSongs.size - 1)
      tableHandler setSelectedIndices updateIndices.toArray
  }
}
