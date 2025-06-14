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

import de.oliver_heger.linedj.extract.id3.model.{ID3FrameExtractor, ID3Header, ID3HeaderExtractor}
import de.oliver_heger.linedj.extract.metadata.MetadataProvider
import org.apache.pekko.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, InHandler}
import org.apache.pekko.stream.{Attributes, Inlet, SinkShape}
import org.apache.pekko.util.ByteString

import scala.concurrent.{Future, Promise}

/**
  * A sink implementation which can extract ID3v2 tags in a binary stream of
  * audio data.
  *
  * This sink is able to detect sections with ID3v2 frames in a stream of audio
  * data. It passes these frames to an [[ID3FrameExtractor]] and yields the
  * resulting [[MetadataProvider]] objects as its materialized value. If no or
  * corrupt ID3 data is found, the materialized value is an empty list.
  *
  * @param tagSizeLimit the maximum size of tags to process
  */
class ID3v2ExtractorSink(tagSizeLimit: Int = Int.MaxValue)
  extends GraphStageWithMaterializedValue[SinkShape[ByteString], Future[List[MetadataProvider]]]:
  val in: Inlet[ByteString] = Inlet[ByteString]("ID3v2ProcessingSink.in")

  override def shape: SinkShape[ByteString] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes):
  (GraphStageLogic, Future[List[MetadataProvider]]) =
    val promiseResult = Promise[List[MetadataProvider]]()

    val logic = new GraphStageLogic(shape) with ID3StateHandler.ID3StateCallback:
      self =>
      /** The current ID3 state handler instance. */
      private var stateHandler = ID3StateHandler(new ID3HeaderExtractor)

      /** The object for extracting ID3 tags. */
      private var frameExtractor: ID3FrameExtractor = _

      /**
        * Stores the providers that have been created for the extracted data.
        */
      private var metadataProviders = List.empty[MetadataProvider]

      /**
        * A flag to indicate the end of ID3 tag extraction. This is used to 
        * figure out when the stream can be completed.
        */
      private var extractionDone = false

      override def preStart(): Unit =
        pull(in)

      setHandler(in, new InHandler:
        override def onUpstreamFinish(): Unit =
          super.onUpstreamFinish()
          setMaterializedValue()

        override def onUpstreamFailure(ex: Throwable): Unit =
          super.onUpstreamFailure(ex)
          promiseResult.failure(ex)

        override def onPush(): Unit =
          stateHandler = stateHandler.handleChunk(grab(in), self)

          if extractionDone then
            completeStage()
            setMaterializedValue()
          else
            pull(in)
      )

      override def frameFound(header: ID3Header): Unit =
        frameExtractor = new ID3FrameExtractor(header, tagSizeLimit)

      override def frameData(data: ByteString, last: Boolean): Unit =
        frameExtractor.addData(data, last)

        if last then
          frameExtractor.createTagProvider().foreach { provider =>
            metadataProviders = provider :: metadataProviders
          }

      override def audioData(data: ByteString): Unit =
        extractionDone = true

      /**
        * Sets the result of this sink in the materialized value if this has
        * not yet been done. This has to be done when all ID3 frames have been
        * processed, but also if the streams ends early before processing of
        * ID3 information is complete.
        */
      private def setMaterializedValue(): Unit =
        promiseResult.trySuccess(metadataProviders)

    (logic, promiseResult.future)
