/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archive.metadata.persistence.parser

import java.nio.charset.StandardCharsets

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
import de.oliver_heger.linedj.archive.metadata.MetaDataProcessingResult
import de.oliver_heger.linedj.archive.metadata.persistence.parser.ParserTypes.Failure
import de.oliver_heger.linedj.shared.archive.media.MediumID

/**
  * A custom processing stage that applies a parser on a source of byte
  * strings. The results produced by the parser are passed downstream.
  */
class ParserStage(parser: MetaDataParser, mediumID: MediumID)
  extends GraphStage[FlowShape[ByteString, MetaDataProcessingResult]] {
  val in: Inlet[ByteString] = Inlet[ByteString]("ParserStage.in")
  val out: Outlet[MetaDataProcessingResult] = Outlet[MetaDataProcessingResult]("ParserStage.out")

  override val shape: FlowShape[ByteString, MetaDataProcessingResult] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      var previousChunk: Option[ByteString] = None
      var lastFailure: Option[Failure] = None

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val chunk = grab(in)
          previousChunk match {
            case None => pull(in)

            case Some(bs) =>
              val (results, nextFailure) =
                parser.processChunk(bs.decodeString(StandardCharsets.UTF_8), mediumID,
                  lastChunk = false, optFailure = lastFailure)
              lastFailure = nextFailure
              println("Temp results:  " + results)
              emitMultiple(out, results.toIterator)
          }
          previousChunk = Some(chunk)
        }

        override def onUpstreamFinish(): Unit = {
          previousChunk match {
            case None => complete(out)

            case Some(bs) =>
              val (results, nextFailure) =
                parser.processChunk(bs.decodeString(StandardCharsets.UTF_8), mediumID,
                  lastChunk = true, optFailure = lastFailure)
              emitMultiple(out, results.toIterator, () => complete(out))
          }
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })
    }
}
