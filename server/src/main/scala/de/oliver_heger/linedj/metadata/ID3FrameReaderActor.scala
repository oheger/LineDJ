/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.metadata

import java.nio.file.Path

import akka.actor.ActorRef
import de.oliver_heger.linedj.io.ChannelHandler.InitFile
import de.oliver_heger.linedj.io.ProcessingReader
import de.oliver_heger.linedj.mp3.{ID3Header, ID3HeaderExtractor}

/**
 * An actor implementation for reading ID3v2 frames from an audio file.
 *
 * This is a processing actor used during meta data extraction from MP3 files.
 * Meta data extraction involves reading the whole MP3 file. This actor is
 * hooked into this process so that it can deal with ID3v2 frames.
 *
 * ID3v2 frames are always located at the beginning of the audio file. This
 * actor checks whether at the current position of the file an ID3 frame header
 * can be read. If this is the case, the content of the frame is requested and
 * passed to the meta data collector actor. From there it is delegated to an
 * instance which actually processes the content and extracts meta data.
 *
 * In order to avoid unrestricted memory consumption, ID3 frames are handled
 * in a single chunk only up to a configurable size. If a frame exceeds this
 * size, it is read in multiple chunks.
 *
 * @param readerActor the underlying reader actor
 * @param extractionContext the extraction context
 */
class ID3FrameReaderActor(override val readerActor: ActorRef, private[metadata] val
extractionContext: MetaDataExtractionContext) extends ProcessingReader {
  /** The path of the file currently processed. */
  private var currentPath: Path = _

  /** The current processing state. */
  private var processingState: ProcessingState = ProcessingStateHeaderSearch

  /**
   * Notifies this actor that a new read operation has been initialized. A
   * concrete implementation can use this callback to perform some
   * initializations. This base implementation is empty
   * @param initFile the ''InitFile'' message
   */
  override protected def readOperationInitialized(initFile: InitFile): Unit = {
    super.readOperationInitialized(initFile)
    currentPath = initFile.path
    processingState = ProcessingStateHeaderSearch
  }

  /**
   * @inheritdoc This implementation checks whether still frame headers are
   *             processed. If so, a request for reading a new header is sent.
   *             Otherwise, no special processing is done, and the method
   *             delegates to the super class.
   */
  override protected def readRequestReceived(count: Int): Unit = {
    processingState handleReadRequestReceived count
  }

  /**
   * @inheritdoc If processing is still active, this implementation checks
   *             whether the received data belongs to a header or is frame
   *             content. In the former case a request for reading frame
   *             content is prepared; in the latter case the frame content is
   *             sent to the collector actor. If all ID3 frames have been
   *             processed, this method delegates to the super method.
   */
  override protected def dataRead(data: Array[Byte]): Unit = {
    processingState = processingState handleDataRead data
  }

  /**
   * Sends a request for reading the data of an ID3 frame header.
   */
  private def readRequestForHeader(): Unit = {
    readFromWrappedActor(ID3HeaderExtractor.ID3HeaderSize)
  }

  /**
   * A trait representing the state of the current processing.
   *
   * Logic for handling several types of messages is encoded in concrete sub
   * classes. This simplifies the implementation because complex conditional
   * logic can be avoided.
   */
  private trait ProcessingState {
    /**
     * Handles a request for reading data. This base implementation forwards
     * this request to the method inherited from ''ProcessingReader''.
     * @param count the number of bytes to be read
     */
    def handleReadRequestReceived(count: Int): Unit = {
      ID3FrameReaderActor.super.readRequestReceived(count)
    }

    /**
     * Handles a chunk of data that has been read. A concrete implementation
     * has to evaluate this data and react accordingly. As result the next
     * state is returned.
     * @param data the data that was read
     * @return the next processing state
     */
    def handleDataRead(data: Array[Byte]): ProcessingState
  }

  /**
   * Represents the processing state that headers for ID3 frames are searched
   * for. This is the initial state.
   */
  private object ProcessingStateHeaderSearch extends ProcessingState {
    /**
     * @inheritdoc This implementation sends a request for a frame header.
     */
    override def handleReadRequestReceived(count: Int): Unit = {
      readRequestForHeader()
    }

    /**
     * @inheritdoc This implementation checks whether the data represents a
     *             valid header. If so, the corresponding frame content is
     *             loaded and processed. Otherwise, frame processing is done.
     */
    override def handleDataRead(data: Array[Byte]): ProcessingState = {
      extractionContext.headerExtractor extractID3Header data match {
        case Some(header) =>
          val readCount = math.min(header.size, extractionContext.config.metaDataReadChunkSize)
          readFromWrappedActor(readCount)
          new ProcessingStateHeaderFound(header, readCount)

        case None =>
          publish(data)
          ProcessingStateDone
      }
    }
  }

  /**
   * Represents the processing state that all frames have been processed. Then
   * data is only read and forwarded to the caller.
   */
  private object ProcessingStateDone extends ProcessingState {
    /**
     * @inheritdoc This implementation just forwards the data to the caller.
     */
    override def handleDataRead(data: Array[Byte]): ProcessingState = {
      ID3FrameReaderActor.super.dataRead(data)
      ProcessingStateDone
    }
  }

  /**
   * Represents the processing state that a header was found and now the
   * content of the associated frame has to be evaluated.
   * @param header the frame header
   * @param bytesProcessed the number of bytes which have been processed
   */
  private class ProcessingStateHeaderFound(header: ID3Header, bytesProcessed: Int) extends
  ProcessingState {
    /**
     * @inheritdoc This implementation forwards the frame content to the
     *             collector actor. If necessary, another chunk of frame
     *             content is requested; otherwise, search for the next header
     *             can start.
     */
    override def handleDataRead(data: Array[Byte]): ProcessingState = {
      val lastChunk = bytesProcessed >= header.size || endOfFile
      extractionContext.collectorActor ! ProcessID3FrameData(currentPath, header, data, lastChunk
        = lastChunk)
      if (lastChunk) {
        readRequestForHeader()
        ProcessingStateHeaderSearch
      } else {
        val nextChunkSize = math.min(extractionContext.config.metaDataReadChunkSize,
          header.size - bytesProcessed)
        readFromWrappedActor(nextChunkSize)
        new ProcessingStateHeaderFound(header, bytesProcessed + nextChunkSize)
      }
    }
  }

}
