/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.radio.actors

import akka.util.ByteString
import scalaz.State

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
  */
private case class MetadataExtractionState(audioChunkSize: Int,
                                   currentChunkSize: Int,
                                   bytesReceived: Int,
                                   audioChunks: List[ByteString],
                                   metadataChunk: ByteString,
                                   inMetadata: Boolean)

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
private trait MetadataExtractionService {
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
  def handleData(data: ByteString): StateUpdate[ExtractedStreamData]
}
