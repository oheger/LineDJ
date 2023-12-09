/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.io.parser

import de.oliver_heger.linedj.io.parser.ParserStage.ChunkSequenceParser
import de.oliver_heger.linedj.io.parser.ParserTypes.Failure
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.util.ByteString

object ParserStage:
  /**
    * Definition of a parser function which can process a chunk of data and
    * return a sequence of partial result objects.
    *
    * This function is used by [[ParserStage]] to process the single chunks of
    * data passed to the stage. The function returns a collection of result
    * objects extracted from the chunk and an optional ''Failure'' object
    * allowing another parse operation to continue at the same position.
    *
    * Parameters are:
    *  - A ''ByteString'' with the current chunk of data
    *  - An optional ''Failure'' from the last parse operation
    *  - A flag whether the current chunk is the last one
    *
    * @tparam A the type of result objects produced by the function
    */
  type ChunkSequenceParser[A] =
    (ByteString, Option[Failure], Boolean) => (Iterable[A], Option[Failure])

/**
  * A custom processing stage that applies a parser on a source of byte
  * strings. The results produced by the parser are passed downstream.
  *
  * This custom stream processing stage is configured with a function for
  * parsing chunks of data. The function is invoked on each chunk, passing in
  * a ''Failure'' object from the last invocation. It returns partial results
  * and another ''Failure'' object allowing processing to continue with the
  * next chunk.
  *
  * @param parser the function for parsing single chunks
  */
class ParserStage[A](parser: ChunkSequenceParser[A])
  extends GraphStage[FlowShape[ByteString, A]]:
  val in: Inlet[ByteString] = Inlet[ByteString]("ParserStage.in")
  val out: Outlet[A] = Outlet[A]("ParserStage.out")

  override val shape: FlowShape[ByteString, A] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape):
      var previousChunk: Option[ByteString] = None
      var lastFailure: Option[Failure] = None

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val chunk = grab(in)
          previousChunk match {
            case None => pull(in)

            case Some(bs) =>
              val (results, nextFailure) = parser(bs, lastFailure, false)
              lastFailure = nextFailure
              if results.nonEmpty then {
                emitMultiple(out, results.iterator)
              } else {
                pull(in)
              }
          }
          previousChunk = Some(chunk)
        }

        override def onUpstreamFinish(): Unit = {
          previousChunk match {
            case None => complete(out)

            case Some(bs) =>
              val (results, _) = parser(bs, lastFailure, true)
              emitMultiple(out, results.iterator, () => complete(out))
          }
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })
