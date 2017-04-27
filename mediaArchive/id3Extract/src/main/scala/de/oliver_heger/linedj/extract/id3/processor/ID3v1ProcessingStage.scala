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

package de.oliver_heger.linedj.extract.id3.processor

import akka.actor.ActorRef
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
import de.oliver_heger.linedj.extract.id3.model.{ID3v1Extractor, TailBuffer}

/**
  * A specialized processing stage for extracting ID3v1 tag information from
  * an mp3 audio file.
  *
  * This stage can be added to a stream that reads an mp3 audio file. It
  * extracts the last 128 bytes block of the file, which may contain and ID3
  * version 1 frame. This data is passed to an extractor. The result of the
  * extraction operation is then passed to a processor actor as an
  * [[de.oliver_heger.linedj.extract.id3.processor.ID3v1MetaData]] message. Note
  * that messages of this type are always sent, even if no valid ID3v1 data
  * was found.
  *
  * @param procActor the processing actor to be notified
  */
class ID3v1ProcessingStage(procActor: ActorRef)
  extends GraphStage[FlowShape[ByteString, ByteString]] {
  val in: Inlet[ByteString] = Inlet[ByteString]("ID3v1ProcessingStage.in")
  val out: Outlet[ByteString] = Outlet[ByteString]("ID3v1ProcessingStage.out")

  override def shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      var tailBuffer = TailBuffer(128)

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val chunk = grab(in)
          push(out, chunk)
          tailBuffer = tailBuffer addData chunk
        }

        override def onUpstreamFinish(): Unit = {
          procActor ! ID3v1MetaData(ID3v1Extractor.providerFor(tailBuffer))
          super.onUpstreamFinish()
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })
    }
}
