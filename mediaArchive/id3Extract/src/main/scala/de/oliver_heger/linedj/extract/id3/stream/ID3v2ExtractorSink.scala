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

import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}

object ID3v2ExtractorSink:
  /**
    * A data type used as result for the functions of the
    * [[ID3StateHandler.ID3StateCallback]] implementation. It covers the
    * information that needs to be transported in the different processing
    * states.
    */
  private enum CallbackResult:
    /**
      * A result indicating that processing of a frame is now done with an
      * optional provider for metadata.
      *
      * @param optProvider the optional provider for metadata
      */
    case FrameResult(optProvider: Option[MetadataProvider])

    /**
      * A result indicating that all ID3 frames have been processed and no more
      * data can be extracted. This is the signal to stop the current stream.
      */
    case ExtractionDone


  /**
    * Finds all [[CallbackResult.FrameResult]] objects in the given list of
    * results and adds the providers they contain to the list of providers.
    *
    * @param results   the list of callback results
    * @param providers the list of (already obtained) providers
    * @return the resulting list of providers
    */
  @tailrec private def gatherMetadataProviders(results: List[CallbackResult],
                                               providers: List[MetadataProvider]): List[MetadataProvider] =
    results match
      case CallbackResult.FrameResult(Some(provider)) :: t =>
        gatherMetadataProviders(t, provider :: providers)
      case _ :: t =>
        gatherMetadataProviders(t, providers)
      case _ => providers
end ID3v2ExtractorSink

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

  import ID3v2ExtractorSink.*

  override def shape: SinkShape[ByteString] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes):
  (GraphStageLogic, Future[List[MetadataProvider]]) =
    val promiseResult = Promise[List[MetadataProvider]]()

    val logic = new GraphStageLogic(shape) with ID3StateHandler.ID3StateCallback[CallbackResult]:
      self =>
      /** The current ID3 state handler instance. */
      private var stateHandler = ID3StateHandler(new ID3HeaderExtractor)

      /** The object for extracting ID3 tags. */
      private var frameExtractor: ID3FrameExtractor = _

      /**
        * Stores the providers that have been created for the extracted data.
        */
      private var metadataProviders = List.empty[MetadataProvider]

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
          val (nextHandler, callbackResults) = stateHandler.handleChunk(grab(in), self)
          stateHandler = nextHandler
          metadataProviders = gatherMetadataProviders(callbackResults, metadataProviders)

          if callbackResults.exists {
            case CallbackResult.ExtractionDone => true
            case _ => false
          } then
            completeStage()
            setMaterializedValue()
          else
            pull(in)
      )

      override def frameFound(header: ID3Header): Option[CallbackResult] =
        frameExtractor = new ID3FrameExtractor(header, tagSizeLimit)
        None

      override def frameData(data: ByteString, last: Boolean): Option[CallbackResult] =
        frameExtractor.addData(data, last)

        if last then
          Some(CallbackResult.FrameResult(frameExtractor.createTagProvider()))
        else
          None

      override def audioData(data: ByteString): Option[CallbackResult] =
        Some(CallbackResult.ExtractionDone)

      /**
        * Sets the result of this sink in the materialized value if this has
        * not yet been done. This has to be done when all ID3 frames have been
        * processed, but also if the streams ends early before processing of
        * ID3 information is complete.
        */
      private def setMaterializedValue(): Unit =
        promiseResult.trySuccess(metadataProviders)

    (logic, promiseResult.future)
