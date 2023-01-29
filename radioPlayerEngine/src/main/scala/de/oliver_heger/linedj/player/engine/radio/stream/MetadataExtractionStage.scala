/*
 * Copyright 2015-2023 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine.radio.stream

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FanOutShape2, Inlet, Outlet}
import akka.util.ByteString

private object MetadataExtractionStage {
  /**
    * Returns an instance of [[MetadataExtractionStage]] that can handle a
    * radio stream with the given optional chunk size for audio data. If the
    * chunk size is undefined, the whole stream is considered to be audio data.
    * Otherwise, metadata is extracted accordingly.
    *
    * @param optAudioChunkSize the optional chunk size for audio data
    * @return a [[MetadataExtractionStage]] to process this radio stream
    */
  def apply(optAudioChunkSize: Option[Int]): MetadataExtractionStage =
    optAudioChunkSize map { chunkSize =>
      new MetadataExtractionStage(SupportedMetadataExtractionService, chunkSize)
    } getOrElse new MetadataExtractionStage(UnsupportedMetadataExtractionService, 0)
}

/**
  * A stage implementation that manages the state of a
  * [[MetadataExtractionService]] to separate a radio stream into the actual
  * audio data and metadata.
  *
  * The stage has two outputs: One yields audio data, the other metadata. If
  * the radio stream does not support metadata, the latter produces no results.
  *
  * @param extractionService the service responsible for metadata extraction
  * @param audioChunkSize    the size of chunks of audio data
  */
private class MetadataExtractionStage private(extractionService: MetadataExtractionService,
                                              audioChunkSize: Int)
  extends GraphStage[FanOutShape2[ByteString, ByteString, ByteString]] {
  val in: Inlet[ByteString] = Inlet[ByteString]("MetadataExtractionStage.in")
  val outAudio: Outlet[ByteString] = Outlet[ByteString]("MetadataExtractionStage.extractedAudioData")
  val outMeta: Outlet[ByteString] = Outlet[ByteString]("MetadataExtractionStage.extractedMetadata")

  override def shape: FanOutShape2[ByteString, ByteString, ByteString] =
    new FanOutShape2[ByteString, ByteString, ByteString](in, outAudio, outMeta)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      /** The current state of the extraction process. */
      private var extractionState = MetadataExtractionState.initial(audioChunkSize)

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          processChunk(grab(in))
        }
      })

      setHandler(outAudio, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })

      setHandler(outMeta, new OutHandler {
        override def onPull(): Unit = {}
      })

      /**
        * Handles a chunk of data received from upstream with the help of the
        * [[MetadataExtractionService]] provided at construction time.
        *
        * @param data the data from upstream
        */
      private def processChunk(data: ByteString): Unit = {
        val stateUpdate = extractionService.handleData(data)

        val (nextState, extracted) = stateUpdate.run(extractionState)
        extractionState = nextState

        if (extracted.audioChunks.nonEmpty) {
          emitMultiple(outAudio, extracted.audioChunks)
        } else {
          pull(in)
        }

        extracted.metadataChunk foreach {
          push(outMeta, _)
        }
      }
    }
}
