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

import de.oliver_heger.linedj.extract.id3.model.{ID3Header, ID3HeaderExtractor}
import org.apache.pekko.util.ByteString

import scala.annotation.tailrec

object ID3StateHandler:
  /**
    * A callback interface that receives notifications during processing of ID3
    * frames. This can be used to further process tag information. All callback
    * methods can return an optional result which is used to communicate
    * processing information to the owner of the state handler.
    *
    * @tparam R the type of the results produced by this callback
    */
  trait ID3StateCallback[R]:
    /**
      * Notifies this callback that an ID3 frame has been found. The header of
      * this frame is passed.
      *
      * @param header the header of the found ID3 frame
      * @return an optional result of this callback function
      */
    def frameFound(header: ID3Header): Option[R] = None

    /**
      * Passes data that belongs to an ID3 frame to this callback. The ''last''
      * parameter indicates that processing of this frame is now complete.
      *
      * @param data the data from the ID3 frame
      * @param last a flag whether this is the last chunk of the frame
      * @return an optional result of this callback function
      */
    def frameData(data: ByteString, last: Boolean): Option[R] = None

    /**
      * Passes data that does not belong to an ID3 frame, but is regular audio
      * data. When this function is invoked, the processing of ID3 frames is
      * complete. The remaining data of the audio file is now passed chunkwise.
      *
      * @param data a chunk of audio data
      * @return an optional result of this callback function
      */
    def audioData(data: ByteString): Option[R] = None
  end ID3StateCallback

  /**
    * Type alias for a function that invokes a callback method on an 
    * [[ID3StateCallback]] object and returns its result.
    */
  private type CallbackAction[R] = ID3StateCallback[R] => Option[R]

  /**
    * Internal data class representing the result of the processing of a data
    * chunk.
    *
    * @param nextState       the next state to switch to
    * @param callbackActions a list of actions send notifications to an
    *                        [[ID3StateCallback]]
    * @param processingDone  flag whether processing is done for the data
    *                        currently available; this is used to determine
    *                        whether another processing result has to be queried
    * @param remainingData   data to be passed to the next state
    * @tparam R the result type of callback actions
    */
  private case class ChunkProcessingResult[R](nextState: ProcessingState,
                                              callbackActions: List[CallbackAction[R]],
                                              processingDone: Boolean = true,
                                              remainingData: Option[ByteString] = None)

  /**
    * A trait representing the current state in processing of ID3 data.
    *
    * The ID3 processing handler can be in different states, depending on the
    * data encountered so far. To avoid complex condition logic, the state is
    * encoded in concrete implementations of this trait. An implementation
    * expects a chunk of data and tries to extract as much ID3 data as
    * possible. This may cause state changes and further actions (e.g. waiting
    * for additional data to arrive). Finally, status changes have to be 
    * reported to the callback.
    */
  private sealed trait ProcessingState:
    /**
      * Handles the specified chunk of data.
      *
      * @param headerExtractor the object to find ID3 headers
      * @param data            the chunk of data
      * @tparam R the result type of callback actions
      * @return an object with processing results
      */
    def handleChunk[R](headerExtractor: ID3HeaderExtractor, data: ByteString): ChunkProcessingResult[R]

  /**
    * A processing state indicating that the header of an ID3 frame is
    * searched for. This is the initial state; it is also set after a frame
    * has been processed.
    *
    * @param dataAvailable the current data available
    */
  private class ProcessingStateFrameSearch(dataAvailable: ByteString = ByteString.empty) extends ProcessingState:
    override def handleChunk[R](headerExtractor: ID3HeaderExtractor, data: ByteString): ChunkProcessingResult[R] =
      val chunk = dataAvailable ++ data
      if chunk.length < ID3HeaderExtractor.ID3HeaderSize then
        ChunkProcessingResult(new ProcessingStateFrameSearch(chunk), Nil)
      else
        headerExtractor.extractID3Header(chunk) match
          case Some(header) =>
            ChunkProcessingResult(
              new ProcessingStateFrameFound(header),
              List(callback => callback.frameFound(header)),
              processingDone = false,
              remainingData = Some(chunk drop ID3HeaderExtractor.ID3HeaderSize)
            )
          case None =>
            ChunkProcessingResult(
              ProcessingStatePassThrough,
              Nil,
              processingDone = false,
              remainingData = Some(chunk)
            )

  /**
    * A processing state indicating that currently an ID3 frame is processed.
    * The header of this frame is passed to the constructor. The state uses 
    * this to figure out when the full data of the frame has been read. Then
    * search for the next header starts.
    *
    * @param header         the header of the current ID3 frame
    * @param bytesProcessed the number of bytes already processed
    */
  private class ProcessingStateFrameFound(header: ID3Header,
                                          bytesProcessed: Int = 0) extends ProcessingState:
    override def handleChunk[R](headerExtractor: ID3HeaderExtractor, data: ByteString): ChunkProcessingResult[R] =
      val remaining = header.size - bytesProcessed
      val isLastChunk = remaining <= data.length
      val (currentChunk, nextChunk) = data.splitAt(remaining)
      val action: CallbackAction[R] = callback => callback.frameData(currentChunk, isLastChunk)

      if data.length < remaining then
        ChunkProcessingResult(
          new ProcessingStateFrameFound(header, bytesProcessed + data.length),
          List(action)
        )
      else
        ChunkProcessingResult(
          new ProcessingStateFrameSearch(),
          List(action),
          remainingData = Some(nextChunk),
          processingDone = nextChunk.isEmpty
        )

  /**
    * A processing state indicating that no more ID3 frames are expected. The
    * remaining data passed through the stage is normal audio data and is
    * ignored.
    */
  private object ProcessingStatePassThrough extends ProcessingState:
    override def handleChunk[R](headerExtractor: ID3HeaderExtractor, data: ByteString): ChunkProcessingResult[R] =
      ChunkProcessingResult(
        ProcessingStatePassThrough,
        callbackActions = List(callback => callback.audioData(data))
      )

  /**
    * Creates a new instance of [[ID3StateHandler]] for processing a new audio
    * file.
    *
    * @param extractor the extractor for ID3 headers
    * @return the [[ID3StateHandler]] instance
    */
  def apply(extractor: ID3HeaderExtractor): ID3StateHandler =
    new ID3StateHandler(extractor, new ProcessingStateFrameSearch())
end ID3StateHandler

/**
  * An internal helper class that handles the processing of ID3 frames while
  * iterating over an audio file. It is used by multiple graph stage
  * implementations.
  *
  * The class processes audio data chunkwise. It supports a callback interface
  * that it invokes when an ID3 frame has been found or fully processed. An
  * implementation of the callback interface can then process the frame data or
  * react on state changes.
  *
  * @param extractor    the extractor for ID3 headers
  * @param currentState the current state of the ID3 frame processing 
  */
private class ID3StateHandler private(extractor: ID3HeaderExtractor,
                                      currentState: ID3StateHandler.ProcessingState):

  import ID3StateHandler.*

  /**
    * Processes the current chunk of data using the current processing state.
    * This method has to be called for each chunk of data of the audio file
    * that is processed. It goes through state transitions until this chunk has
    * been fully processed and also invokes the callback accordingly. The 
    * return value is a tuple with an updated [[ID3StateHandler]] instance to
    * continue processing with and a list with the collected results returned
    * by callback functions.
    *
    * @param chunk    the chunk of data to be processed
    * @param callback the callback to report processing state changes
    * @tparam R the type of results returned by the callback
    * @return an updated [[ID3StateHandler]] object to continue processing and
    *         a list with defined results returned by the callback
    */
  def handleChunk[R](chunk: ByteString, callback: ID3StateCallback[R]): (ID3StateHandler, List[R]) =
    @tailrec def handleChunkInState(state: ProcessingState,
                                    data: ByteString,
                                    callbackResults: List[Option[R]]): (ProcessingState, List[R]) =
      val result = state.handleChunk[R](extractor, data)
      val nextCallbackResults = result.callbackActions.map(action => action(callback)) ::: callbackResults
      if result.processingDone then
        (result.nextState, nextCallbackResults.flatten)
      else
        handleChunkInState(result.nextState, result.remainingData.getOrElse(ByteString.empty), nextCallbackResults)

    val (nextState, results) = handleChunkInState(currentState, chunk, Nil)
    val nextHandler = if nextState == currentState then this
    else new ID3StateHandler(extractor, nextState)
    (nextHandler, results)
