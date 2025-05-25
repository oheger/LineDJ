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
import de.oliver_heger.linedj.extract.id3.stream.ID3v2ExtractorStage.{ChunkProcessingResult, ProcessingContext, ProcessingState, ProcessingStateFrameSearch}
import de.oliver_heger.linedj.extract.metadata.MetadataProvider
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.util.ByteString

import scala.annotation.tailrec

object ID3v2ExtractorStage:
  /**
    * An internally used data class that stores important information required
    * by [[ProcessingState]] implementations.
    *
    * @param headerExtractor the object to extract ID3 headers
    * @param tagSizeLimit    the maximum size of a tag to be processed
    */
  private case class ProcessingContext(headerExtractor: ID3HeaderExtractor,
                                       tagSizeLimit: Int)

  /**
    * Internal data class representing the result of a single ID3 extraction
    * operation.
    *
    * @param nextState      the next state to switch to
    * @param processingDone flag whether processing is done for the data
    *                       currently available; this is used to determine
    *                       whether another processing result has to be queried
    * @param remainingData  data to be passed to the next state
    * @param result         an optional processing result
    * @param extractionDone flag whether the extraction operation is now
    *                       complete; if '''true''', the stage completes itself
    */
  private case class ChunkProcessingResult(nextState: ProcessingState,
                                           processingDone: Boolean = true,
                                           remainingData: Option[ByteString] = None,
                                           result: Option[MetadataProvider] = None,
                                           extractionDone: Boolean = false)

  /**
    * A trait representing the current state in processing of ID3 data.
    *
    * The ID3 processing stage can be in different states, depending on the
    * data encountered so far. To avoid complex condition logic, the state is
    * encoded in concrete implementations of this trait. An implementation
    * expects a chunk of data and tries to extract as much ID3 data as
    * possible. This may cause state changes and further actions (e.g. waiting
    * for additional data to arrive). Finally, the results to be passed
    * downstream have to be determined.
    */
  private sealed trait ProcessingState:
    /**
      * Handles the specified chunk of data.
      *
      * @param context the processing context
      * @param data    the chunk of data
      * @return an object with processing results
      */
    def handleChunk(context: ProcessingContext,
                    data: ByteString): ChunkProcessingResult

  /**
    * A processing state indicating that the header of an ID3 frame is
    * searched for. This is the initial state; it is also set after a frame
    * has been processed.
    *
    * @param dataAvailable the current data available
    */
  private class ProcessingStateFrameSearch(dataAvailable: ByteString = ByteString.empty) extends ProcessingState:
    override def handleChunk(context: ProcessingContext, data: ByteString): ChunkProcessingResult =
      val chunk = dataAvailable ++ data
      if chunk.length < ID3HeaderExtractor.ID3HeaderSize then
        ChunkProcessingResult(new ProcessingStateFrameSearch(chunk))
      else
        context.headerExtractor.extractID3Header(chunk) match
          case Some(header) =>
            ChunkProcessingResult(
              new ProcessingStateFrameFound(new ID3FrameExtractor(header, context.tagSizeLimit)),
              processingDone = false,
              remainingData = Some(chunk drop ID3HeaderExtractor.ID3HeaderSize)
            )
          case None =>
            ChunkProcessingResult(
              ProcessingStatePassThrough,
              processingDone = false,
              remainingData = Some(chunk)
            )

  /**
    * A processing state indicating that currently an ID3 frame is processed.
    * This is done with the help of an [[ID3FrameExtractor]] managed by this
    * instance. The whole data of the frame has to be passed to this extractor.
    * After this is done, a result can be produced, and search for the next
    * header starts.
    *
    * @param frameExtractor the [[ID3FrameExtractor]] to extract tag
    *                       information
    * @param bytesProcessed the number of bytes already processed
    */
  private class ProcessingStateFrameFound(frameExtractor: ID3FrameExtractor,
                                          bytesProcessed: Int = 0) extends ProcessingState:
    override def handleChunk(context: ProcessingContext, data: ByteString): ChunkProcessingResult =
      val remaining = frameExtractor.header.size - bytesProcessed
      val isLastChunk = remaining <= data.length
      val (currentChunk, nextChunk) = data.splitAt(remaining)
      val nextExtractor = frameExtractor.addData(currentChunk, isLastChunk)

      if data.length < remaining then
        ChunkProcessingResult(
          new ProcessingStateFrameFound(nextExtractor, bytesProcessed + data.length)
        )
      else
        ChunkProcessingResult(
          new ProcessingStateFrameSearch(),
          result = nextExtractor.createTagProvider(),
          remainingData = Some(nextChunk),
          processingDone = nextChunk.isEmpty
        )

  /**
    * A processing state indicating that no more ID3 frames are expected. The
    * remaining data passed through the stage is normal audio data and is
    * ignored.
    */
  private object ProcessingStatePassThrough extends ProcessingState:
    override def handleChunk(context: ProcessingContext, data: ByteString): ChunkProcessingResult =
      ChunkProcessingResult(ProcessingStatePassThrough, extractionDone = true)
end ID3v2ExtractorStage

/**
  * A stage implementation which can extract ID3v2 tags in a binary stream of
  * audio data.
  *
  * This stage is able to detect ID3v2 frames in a stream of audio data. It
  * passes these frames to an [[ID3FrameExtractor]] and yields the resulting
  * [[MetadataProvider]] as output. If no or corrupt ID3 data is found, the
  * stage does not push anything downstream.
  *
  * @param tagSizeLimit the maximum size of tags to process
  */
class ID3v2ExtractorStage(tagSizeLimit: Int = Int.MaxValue)
  extends GraphStage[FlowShape[ByteString, MetadataProvider]]:
  val in: Inlet[ByteString] = Inlet[ByteString]("ID3v2ProcessingStage.in")
  val out: Outlet[MetadataProvider] = Outlet[MetadataProvider]("ID3v2ProcessingStage.out")

  override def shape: FlowShape[ByteString, MetadataProvider] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape):
      /** The object storing information for ID3 data processing. */
      private val processingContext = ProcessingContext(new ID3HeaderExtractor, tagSizeLimit)

      /** The current state this stage is in. */
      private var state: ProcessingState = new ProcessingStateFrameSearch()

      setHandler(in, new InHandler:
        override def onPush(): Unit =
          val (result, providers) = handleChunk(state, grab(in), Nil)
          state = result.nextState

          val closeIfDone = () =>
            if result.extractionDone then
              completeStage()
          if providers.nonEmpty then
            emitMultiple(out, providers, andThen = closeIfDone)
          else if providers.isEmpty then
            pull(in)
      )

      setHandler(out, new OutHandler:
        override def onPull(): Unit =
          pull(in)
      )

      /**
        * Processes the current chunk of data using the current processing
        * state. This method is called for each chunk of data passed through
        * the stream. It goes through state transitions until the chunk of data
        * has been fully processed. The return value then indicates what to do
        * next. Also, stream results to be passed downstream are collected and
        * returned.
        *
        * @param currentState the current processing state
        * @param chunk        the chunk of data to be processed
        * @param providers    an aggregated list of [[MetadataProvider]]
        *                     objects to be passed downstream
        * @return a tuple with the results of the processing step and the list
        *         of providers to pass downstream
        */
      @tailrec private def handleChunk(currentState: ProcessingState,
                                       chunk: ByteString,
                                       providers: List[MetadataProvider]):
      (ChunkProcessingResult, List[MetadataProvider]) =
        val result = currentState.handleChunk(processingContext, chunk)
        val nextProviders = result.result.fold(providers) { p =>
          p :: providers
        }
        if result.processingDone then (result, nextProviders)
        else handleChunk(result.nextState, result.remainingData getOrElse ByteString.empty, nextProviders)
