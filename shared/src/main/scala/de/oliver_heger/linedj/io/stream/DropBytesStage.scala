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

package de.oliver_heger.linedj.io.stream

import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.util.ByteString

/**
  * An implementation of a flow stage that drops a given number of bytes from a
  * stream of [[ByteString]] chunks.
  *
  * This stage is useful when skipping to a specific position in a data source,
  * e.g. to resume playback of a media file at the position where it was
  * stopped. The first bytes of the stream are skipped; once the offset has been
  * reached, all subsequent data is passed through unchanged.
  *
  * @param offset the number of bytes to drop from the beginning of the stream
  */
class DropBytesStage(offset: Long) extends GraphStage[FlowShape[ByteString, ByteString]]:
  val in: Inlet[ByteString] = Inlet[ByteString]("DropBytesStage.in")
  val out: Outlet[ByteString] = Outlet[ByteString]("DropBytesStage.out")

  override def shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape):
      /** The number of bytes still to drop. */
      private var remaining: Long = offset

      setHandler(in, new InHandler:
        override def onPush(): Unit =
          val chunk = grab(in)
          if remaining > 0 then
            if chunk.size <= remaining then
              remaining -= chunk.size
              pull(in)
            else
              val dropped = chunk.drop(remaining.toInt)
              remaining = 0
              push(out, dropped)
          else
            push(out, chunk)
      )

      setHandler(out, new OutHandler:
        override def onPull(): Unit =
          pull(in)
      )
