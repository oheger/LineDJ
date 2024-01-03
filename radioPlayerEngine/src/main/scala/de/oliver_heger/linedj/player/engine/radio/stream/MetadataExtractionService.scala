/*
 * Copyright 2015-2024 The Developers Team.
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

import org.apache.pekko.util.ByteString
import scalaz.State
import scalaz.State._

import scala.annotation.tailrec

private object MetadataExtractionState:
  /**
    * Returns an initial [[MetadataExtractionState]] object with the given size
    * of audio chunks.
    *
    * @param audioChunkSize the chunk size for audio data
    * @return the initial state object
    */
  def initial(audioChunkSize: Int): MetadataExtractionState =
    MetadataExtractionState(audioChunkSize = audioChunkSize,
      currentChunkSize = audioChunkSize,
      bytesReceived = 0,
      audioChunks = List.empty,
      metadataChunk = ByteString.empty,
      lastMetadata = None,
      inMetadata = false)

/**
  * A data class representing the current state of metadata extraction. An
  * instance of this class is processed by [[MetadataExtractionService]].
  *
  * @param audioChunkSize   the size of chunks with audio data
  * @param currentChunkSize the size of the current chunk to process
  * @param bytesReceived    the number of bytes received for the current chunk
  * @param audioChunks      the audio chunks collected so far
  * @param metadataChunk    the metadata collected so far
  * @param inMetadata       flag whether metadata is currently processed
  * @param lastMetadata     the last extracted metadata if any
  */
private case class MetadataExtractionState(audioChunkSize: Int,
                                           currentChunkSize: Int,
                                           bytesReceived: Int,
                                           audioChunks: List[ByteString],
                                           metadataChunk: ByteString,
                                           inMetadata: Boolean,
                                           lastMetadata: Option[ByteString])

/**
  * A data class storing the different types of data chunks that have been
  * extracted. An instance of this class is returned by
  * [[MetadataExtractionService]]. Based on this information, audio data can be
  * separated from metadata.
  *
  * @param audioChunks   a list with chunks of audio data
  * @param metadataChunk an option with the latest chunk of metadata
  */
private case class ExtractedStreamData(audioChunks: List[ByteString],
                                       metadataChunk: Option[ByteString])

/**
  * Interface of a service that allows the extraction of metadata from a radio
  * stream according to the Shoutcast Metadata Protocol [1].
  *
  * This protocol issues blocks of metadata with a variable size into
  * fixed-sized chunks of audio data. This service is invoked with the binary
  * data read from the audio stream. It keeps track on the state of the block
  * sizes received so far. Based on this information, the audio data can be
  * separated from the metadata.
  *
  * [1] https://gist.github.com/dimitris-c/3e2af7ab451c965d126c02ab580f1eb8
  */
private trait MetadataExtractionService:
  /** Type for a state update yielding a specific result. */
  type StateUpdate[T] = State[MetadataExtractionState, T]

  /**
    * Updates the state for a new block of stream data that has been received.
    * Extracts the data that can be extracted at that point of time.
    *
    * @param data the data from the stream
    * @return the updated state
    */
  def dataReceived(data: ByteString): StateUpdate[Unit]

  /**
    * Updates the state by removing the data that has been extracted so far.
    * The corresponding data is returned, so that it can be processed.
    *
    * @return the updated state and the extracted data
    */
  def extractedData(): StateUpdate[ExtractedStreamData]

  /**
    * Handles a block of data from the stream by updating the state accordingly
    * and returning the extracted data.
    *
    * @param data the data from the stream
    * @return the updated state and the extracted data
    */
  def handleData(data: ByteString): StateUpdate[ExtractedStreamData] = for
    _ <- dataReceived(data)
    next <- extractedData()
  yield next

/**
  * An implementation of [[MetadataExtractionService]] that is used when the
  * audio stream of an internet radio station supports metadata. It then
  * handles the separation of metadata from audio data according to the
  * Shoutcast Metadata Protocol.
  */
private object SupportedMetadataExtractionService extends MetadataExtractionService:
  /**
    * Updates the state for a new block of stream data that has been received.
    * Extracts the data that can be extracted at that point of time.
    *
    * @param data the data from the stream
    * @return the updated state
    */
  override def dataReceived(data: ByteString): StateUpdate[Unit] = modify { s =>
    @tailrec
    def updateState(state: MetadataExtractionState, data: ByteString): MetadataExtractionState =
      if state.bytesReceived == state.currentChunkSize then
        if state.inMetadata then
          val nextState = state.copy(inMetadata = false, currentChunkSize = state.audioChunkSize, bytesReceived = 0)
          updateState(nextState, data)
        else
          val currentMetadataSize = metadataSize(data)
          val nextState = if currentMetadataSize > 0 then
            state.copy(inMetadata = true, currentChunkSize = currentMetadataSize, bytesReceived = 0,
              metadataChunk = ByteString.empty)
          else state.copy(bytesReceived = 0)
          updateState(nextState, data.drop(1))
      else

        val (current, optNext) = splitBlockToChunkSize(state, data)
        val bytesReceived = state.bytesReceived + current.size
        val nextState = if state.inMetadata then
          state.copy(bytesReceived = bytesReceived, metadataChunk = state.metadataChunk ++ current)
        else
          state.copy(bytesReceived = bytesReceived, audioChunks = current :: state.audioChunks)
        optNext match
          case Some(nextBlock) => updateState(nextState, nextBlock)
          case None => nextState

    updateState(s, data)
  }

  /**
    * Updates the state by removing the data that has been extracted so far.
    * The corresponding data is returned, so that it can be processed.
    *
    * @return the updated state and the extracted data
    */
  override def extractedData(): StateUpdate[ExtractedStreamData] = State { s =>
    val (extractedMetadata, nextMetadata) =
      if !s.inMetadata && !s.metadataChunk.isEmpty || s.inMetadata && s.bytesReceived >= s.currentChunkSize then
        (Some(s.metadataChunk), ByteString.empty)
      else (None, s.metadataChunk)

    val deDuplicatedMetadata = extractedMetadata.filterNot(s.lastMetadata.contains)
    val nextLastMetadata = deDuplicatedMetadata orElse s.lastMetadata
    val extracted = ExtractedStreamData(audioChunks = s.audioChunks.reverse, deDuplicatedMetadata)
    val nextState = s.copy(audioChunks = List.empty, metadataChunk = nextMetadata, lastMetadata = nextLastMetadata)
    (nextState, extracted)
  }

  /**
    * Computes the size of a chunk of metadata from the given data. The length
    * is encoded in the first byte of the data block.
    *
    * @param data the block of data
    * @return the length of the next chunk of metadata
    */
  private def metadataSize(data: ByteString): Int = (data(0) & 0xFF) * 16

  /**
    * Splits the given block of data into a part that fits into the current
    * remaining chunk size and an optional part of data for the next chunk.
    * This part is ''None'' if the data fits completely into the current chunk.
    *
    * @param state the current state
    * @param data  the block of data
    * @return the split data
    */
  private def splitBlockToChunkSize(state: MetadataExtractionState, data: ByteString):
  (ByteString, Option[ByteString]) =
    if state.bytesReceived + data.size <= state.currentChunkSize then (data, None)
    else
      val (current, next) = data.splitAt(state.currentChunkSize - state.bytesReceived)
      (current, Some(next))

/**
  * An implementation of [[MetadataExtractionService]] that is used for radio
  * stations that do not support metadata in their audio streams. Therefore,
  * all received data is interpreted as audio data and passed through directly.
  */
private [stream] object UnsupportedMetadataExtractionService extends MetadataExtractionService:
  /**
    * Updates the state for a new block of stream data that has been received.
    * Extracts the data that can be extracted at that point of time.
    *
    * @param data the data from the stream
    * @return the updated state
    */
  override def dataReceived(data: ByteString): StateUpdate[Unit] = modify { s =>
    s.copy(audioChunks = data :: s.audioChunks)
  }

  /**
    * Updates the state by removing the data that has been extracted so far.
    * The corresponding data is returned, so that it can be processed.
    *
    * @return the updated state and the extracted data
    */
  override def extractedData(): StateUpdate[ExtractedStreamData] = State { s =>
    (s.copy(audioChunks = List.empty), ExtractedStreamData(s.audioChunks.reverse, None))
  }
