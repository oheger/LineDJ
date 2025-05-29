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

import de.oliver_heger.linedj.extract.id3.model.{Mp3DataExtractor, Mp3Metadata}
import org.apache.pekko.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, InHandler}
import org.apache.pekko.stream.{Attributes, Inlet, SinkShape}
import org.apache.pekko.util.ByteString

import scala.concurrent.{Future, Promise}

/**
  * A sink implementation which can extract metadata about the MP3 version of
  * an audio file.
  *
  * This sink consumes the full content of an audio file. It makes use of an
  * [[Mp3DataExtractor]] to gather information about this file and produces a
  * corresponding [[Mp3Metadata]] object as its materialized result.
  */
class Mp3DataExtractorSink extends GraphStageWithMaterializedValue[SinkShape[ByteString], Future[Mp3Metadata]]:
  val in: Inlet[ByteString] = Inlet[ByteString]("ID3v1ProcessingSink.in")

  override def shape: SinkShape[ByteString] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes):
  (GraphStageLogic, Future[Mp3Metadata]) =
    val promiseResult = Promise[Mp3Metadata]()

    val logic = new GraphStageLogic(shape):
      /** The extractor for MP3 metadata. */
      private val extractor = new Mp3DataExtractor

      override def preStart(): Unit =
        pull(in)

      setHandler(in, new InHandler:
        override def onPush(): Unit =
          extractor.addData(grab(in))
          pull(in)

        override def onUpstreamFinish(): Unit =
          super.onUpstreamFinish()
          promiseResult.success(createMetadata())

        override def onUpstreamFailure(ex: Throwable): Unit =
          super.onUpstreamFailure(ex)
          promiseResult.failure(ex)
      )

      /**
        * Obtains the resulting [[Mp3Metadata]] object from the extractor.
        *
        * @return the metadata that has been aggregated during processing
        */
      private def createMetadata(): Mp3Metadata =
        Mp3Metadata(
          version = extractor.getVersion,
          layer = extractor.getLayer,
          sampleRate = extractor.getSampleRate,
          minimumBitRate = extractor.getMinBitRate,
          maximumBitRate = extractor.getMaxBitRate,
          duration = extractor.getDuration.toInt
        )

    (logic, promiseResult.future)
    