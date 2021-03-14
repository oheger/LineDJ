/*
 * Copyright 2015-2021 The Developers Team.
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

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString

/**
  * A specialized graph stage implementation that processes a list of elements
  * by inserting a separator between the single elements.
  *
  * This stage is intended for streams that transform lists of elements to
  * text-based formats like JSON arrays. It operates similar to Scala's
  * ''mkString'' function: it inserts a prefix, a suffix, and separator
  * characters between the single elements.
  *
  * A function has to be specified to map the single elements to strings. The
  * function is also passed the index of the current element.
  *
  * @param prefix    a prefix string to be printed before the first element
  * @param separator the separator between stream elements
  * @param suffix    a suffix string to be printed after the last element
  * @param f         the transformation function from elements to string
  * @tparam A the type of elements processed by this stage
  */
class ListSeparatorStage[A](prefix: String, separator: => String, suffix: String)
                           (f: (A, Int) => String) extends GraphStage[FlowShape[A, ByteString]] {
  val in: Inlet[A] = Inlet[A]("ListSeparatorStage.in")
  val out: Outlet[ByteString] = Outlet[ByteString]("ListSeparator.out")

  override val shape: FlowShape[A, ByteString] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      private lazy val delimiter = separator // access only once

      private var index = 0

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val elemSeparator = if (index == 0) prefix else delimiter
          push(out, ByteString(elemSeparator + f(grab(in), index)))
          index += 1
        }

        override def onUpstreamFinish(): Unit = {
          val data = if (index > 0) suffix else prefix + suffix
          emit(out, ByteString(data))
          completeStage()
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })
    }
}
