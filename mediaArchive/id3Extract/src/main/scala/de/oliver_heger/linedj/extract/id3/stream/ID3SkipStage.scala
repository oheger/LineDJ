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

package de.oliver_heger.linedj.extract.id3.stream

import de.oliver_heger.linedj.extract.id3.model.ID3HeaderExtractor
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.util.ByteString

/**
  * An implementation of a flow stage that skips ID3 frames from audio data.
  *
  * This stage filters out ID3v2 frames from a stream with MP3 audio
  * information; only pure audio data is passed. Note that an ID3v1 frame at
  * the end of the stream is not skipped.
  *
  * This stage can be used if no ID3 tags are needed or even can cause
  * problems, e.g. if audio data is downloaded from an archive for playback
  * only.
  */
class ID3SkipStage extends GraphStage[FlowShape[ByteString, ByteString]]:
  val in: Inlet[ByteString] = Inlet[ByteString]("ID3SkipStage.in")
  val out: Outlet[ByteString] = Outlet[ByteString]("ID3SkipStage.out")

  override def shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with ID3StateHandler.ID3StateCallback[ByteString]:
      self =>
      /** The current ID3 state handler instance. */
      private var stateHandler = ID3StateHandler(new ID3HeaderExtractor)

      setHandler(in, new InHandler:
        override def onPush(): Unit =
          val (nextHandler, results) = stateHandler.handleChunk(grab(in), self)
          stateHandler = nextHandler

          results.headOption match
            case Some(data) => push(out, data)
            case None => pull(in)
      )

      setHandler(out, new OutHandler:
        override def onPull(): Unit =
          pull(in)
      )

      override def audioData(data: ByteString): Option[ByteString] =
        Some(data)
