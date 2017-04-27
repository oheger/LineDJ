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
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.util.ByteString
import de.oliver_heger.linedj.extract.id3.model.{ID3Header, ID3HeaderExtractor}
import de.oliver_heger.linedj.extract.id3.processor.ID3v2ProcessingStage.{ChunkProcessingResult,
ProcessingState, ProcessingStateFrameSearch}

import scala.annotation.tailrec

object ID3v2ProcessingStage {

  /**
    * Internal data class representing the result of a single ID3 extraction
    * operation.
    *
    * @param nextState      the next state to switch to
    * @param processingDone flag whether processing is done for the data
    *                       currently available
    * @param downStreamData data to be passed downstream
    * @param resultMsg      a message to be sent to the processor actor
    */
  private case class ChunkProcessingResult(nextState: ProcessingState,
                                           processingDone: Boolean = true,
                                           downStreamData: Option[ByteString] = None,
                                           resultMsg: Option[Any] = None)

  /**
    * A trait representing the current state in processing of ID3 data.
    *
    * The ID3 processing stage can be in different states, depending on the
    * data encountered so far. To avoid complex condition logic, the state is
    * encoded in concrete implementations of this trait. An implementation
    * expects a chunk of data and tries to extract as much ID3 data as
    * possible. This may cause state changes and further actions (e.g. waiting
    * for additional data to arrive). Finally, data to be passed downstream
    * has to be determined. (Extracted ID3 data is not passed downstream,
    * but audio data which comes after the ID3 frames.)
    */
  private sealed trait ProcessingState {
    /**
      * Handles the specified chunk of data.
      *
      * @param extractor the ID3 header extractor
      * @param data      the chunk of data
      * @return an object with processing results
      */
    def handleChunk(extractor: ID3HeaderExtractor,
                    data: ByteString): ChunkProcessingResult

    /**
      * Returns a message to be sent to the processor actor if the stream is
      * completed in this state. This is used to handle corrupt or incomplete
      * ID3 data.
      *
      * @return an optional completion message to the processor actor
      */
    def completionMessage: Option[Any] = None
  }

  /**
    * A processing state indicating that the header of an ID3 frame is
    * searched for. This is the initial state; it is also set after a frame
    * has been processed.
    *
    * @param dataAvailable the current data available
    */
  private class ProcessingStateFrameSearch(dataAvailable: ByteString = ByteString.empty)
    extends ProcessingState {
    override def handleChunk(extractor: ID3HeaderExtractor,
                             data: ByteString): ChunkProcessingResult = {
      val chunk = dataAvailable ++ data
      if (chunk.length < ID3HeaderExtractor.ID3HeaderSize)
        ChunkProcessingResult(new ProcessingStateFrameSearch(chunk))
      else {
        extractor extractID3Header chunk match {
          case Some(header) =>
            ChunkProcessingResult(new ProcessingStateFrameFound(header),
              processingDone = false,
              downStreamData = Some(chunk drop ID3HeaderExtractor.ID3HeaderSize))
          case None =>
            ChunkProcessingResult(ProcessingStatePassThrough, processingDone = false,
              downStreamData = Some(chunk))
        }
      }
    }
  }

  /**
    * A processing state indicating that currently an ID3 frame is processed.
    * The whole data of the frame has to be passed to the processing actor.
    * After this is done, search for the next header starts.
    *
    * @param header         the header of the current ID3 frame
    * @param bytesProcessed the number of bytes already processed
    */
  private class ProcessingStateFrameFound(header: ID3Header, bytesProcessed: Int = 0)
    extends ProcessingState {
    override def handleChunk(extractor: ID3HeaderExtractor, data: ByteString):
    ChunkProcessingResult = {
      val remaining = header.size - bytesProcessed
      val msg = ProcessID3FrameData(header, data take remaining,
        lastChunk = remaining <= data.length)
      if (data.length < remaining)
        ChunkProcessingResult(new ProcessingStateFrameFound(header,
          bytesProcessed + data.length), resultMsg = Some(msg))
      else ChunkProcessingResult(new ProcessingStateFrameSearch(),
        processingDone = false, resultMsg = Some(msg),
        downStreamData = Some(data drop remaining))
    }

    /**
      * @inheritdoc This implementation returns a message indicating an
      *             incomplete ID3 frame.
      */
    override def completionMessage = Some(IncompleteID3Frame(header))
  }

  /**
    * A processing state indicating that no more ID3 frames are expected. The
    * remaining data passed through the stage is normal audio data and has to
    * be passed downstream.
    */
  private object ProcessingStatePassThrough extends ProcessingState {
    override def handleChunk(extractor: ID3HeaderExtractor, data: ByteString):
    ChunkProcessingResult =
      ChunkProcessingResult(ProcessingStatePassThrough, downStreamData = Some(data))
  }

}

/**
  * A stage implementation which can process ID3v2 tags in a binary stream of
  * audio data.
  *
  * This stage is able to detect ID3v2 frames in a stream of audio data. The
  * frames are filtered out and are not passed downstream. If a processor actor
  * is specified, the content of these frames is sent to it as
  * [[ProcessID3FrameData]] messages.
  *
  * If ID3 data is corrupt and the stream ends in the middle of a frame, an
  * [[IncompleteID3Frame]] message is sent to the processor actor. This
  * guarantees that the end of a frame can always be determined.
  *
  * @param procActor an optional processor actor reference
  */
class ID3v2ProcessingStage(procActor: Option[ActorRef])
  extends GraphStage[FlowShape[ByteString, ByteString]] {
  val in: Inlet[ByteString] = Inlet[ByteString]("ID3v2ProcessingStage.in")
  val out: Outlet[ByteString] = Outlet[ByteString]("ID3v2ProcessingStage.out")

  override def shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      /** The extractor for ID3 headers. */
      private val headerExtractor = new ID3HeaderExtractor

      /** The current state this stage is in. */
      private var state: ProcessingState = new ProcessingStateFrameSearch()

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val result = handleChunk(state, grab(in))
          state = result.nextState
          if (result.downStreamData.isDefined)
            push(out, result.downStreamData.get)
          else pull(in)
        }

        override def onUpstreamFinish(): Unit = {
          notifyProcessorActor(state.completionMessage)
          super.onUpstreamFinish()
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })

      /**
        * Processes the current chunk of data using the current processing state.
        * This method is called for each chunk of data passed through the
        * stream. It goes through state transitions until the chunk of data has
        * been fully processed. The return value then indicates what to do next.
        *
        * @param currentState the current processing state
        * @param chunk        the chunk of data to be processed
        * @return an object with the results of the processing step
        */
      @tailrec private def handleChunk(currentState: ProcessingState, chunk: ByteString):
      ChunkProcessingResult = {
        val result = currentState.handleChunk(headerExtractor, chunk)
        notifyProcessorActor(result.resultMsg)
        if (result.processingDone) result
        else handleChunk(result.nextState, result.downStreamData getOrElse ByteString.empty)
      }

      /**
        * Sends the message for a processing step to the processor actor if it
        * is defined.
        *
        * @param msg an option for the message to be sent
        */
      private def notifyProcessorActor(msg: Option[Any]): Unit = {
        for {a <- procActor
             m <- msg
        } a ! m
      }
    }
}
