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

package de.oliver_heger.linedj.io.stream

import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.util.ByteString

/**
  * A custom ''GraphStage'' implementation which only accepts a configurable
  * number of data.
  *
  * The flow stage receives ''ByteString'' objects that are passed downstream
  * without changes. The size of data processed is accumulated. If it exceeds a
  * given limit, the stage is canceled with a failure.
  *
  * @param maxSize the maximum size of the source in bytes
  */
class StreamSizeRestrictionStage(val maxSize: Int) extends GraphStage[FlowShape[ByteString,
  ByteString]]:
  val in: Inlet[ByteString] = Inlet[ByteString]("SizeRestrictionStage.in")
  val out: Outlet[ByteString] = Outlet[ByteString]("SizeRestrictionStage.out")

  override val shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape):
      var bytesRead = 0

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val chunk = grab(in)
          bytesRead += chunk.size
          if bytesRead > maxSize then {
            fail(out, new IllegalStateException(s"Size limit of $maxSize exceeded!"))
          } else {
            push(out, chunk)
          }
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })
