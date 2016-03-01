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

package de.oliver_heger.linedj.metadata.persistence.parser

import scala.language.higherKinds

/**
  * A trait describing a parser that supports chunk-wise parsing.
  *
  * This trait adds a single method for running a parser on a chunk of data.
  * This can be either a new chunk, or parsing can continue after a failure.
  *
  * @tparam Parser the type of the concrete parsers implementation
  * @tparam R      the result type of the parser
  * @tparam F      the failure type of the parser
  */
trait ChunkParser[Parser[+ _], R[_], F] extends Parsers[Parser] {
  /**
    * Runs the specified parser on a chunk of data. This method can be used to
    * start a new parse operation or to continue an interrupted one. In the
    * latter case, a ''Failure'' object has to be provided so that the
    * operation can continue in the very state and position.
    *
    * @param p          the parser to be executed
    * @param nextChunk  the data for the next chunk to be parsed
    * @param lastChunk  a flag whether this is the last chunk
    * @param optFailure an optional ''Failure'' to continue a parse operation
    * @tparam A the result type of the parser
    * @return the result produced by the parser
    */
  def runChunk[A](p: Parser[A])(nextChunk: String, lastChunk: Boolean,
                                optFailure: Option[F] = None): R[A]
}
