/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.ui.playlist.plexport

import de.oliver_heger.linedj.pleditor.ui.playlist.plexport.CopyFileActor.{CopyMediumFile, CopyProgress}
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.util.ByteString

/**
  * A stage which sends progress notifications during a copy operation.
  *
  * @param receiver     the actor which receives progress notifications
  * @param copyRequest  the current copy request
  * @param progressSize the number of bytes after which a notification is to be
  *                     sent
  */
private class CopyProgressNotificationStage(receiver: ActorRef,
                                            copyRequest: CopyMediumFile,
                                            progressSize: Int)
  extends GraphStage[FlowShape[ByteString, ByteString]]:
  val in: Inlet[ByteString] = Inlet("CopyProgressNotificationStage.in")
  val out: Outlet[ByteString] = Outlet("CopyProgressNotificationStage.out")

  override def shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape):
      /** A counter for the number of bytes that have been written. */
      private var bytesWritten = 0

      /** A counter for the progress of a copy operation. */
      private var copyProgressCount = 0

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val chunk = grab(in)
          push(out, chunk)
          bytesWritten += chunk.size
          val progressCount = bytesWritten / progressSize
          if copyProgressCount != progressCount then {
            copyProgressCount = progressCount
            receiver ! CopyProgress(copyRequest, progressCount * progressSize)
          }
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })
