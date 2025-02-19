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

package de.oliver_heger.linedj.extract.id3.processor

import de.oliver_heger.linedj.extract.id3.model.ID3Header
import de.oliver_heger.linedj.extract.metadata.MetadataProvider
import org.apache.pekko.util.ByteString

/**
  * A message containing data extracted from an ID3 frame to be processed.
  *
  * Messages of this type are produced for the binary content of ID3v2 frames
  * found in an MP3 audio file. Depending on the size of the frame, multiple
  * messages of this type will be generated. The ''lastChunk'' parameter
  * indicates whether a complete frame has been processed.
  *
  * @param frameHeader the header of the affected ID3v2 frame
  * @param data        binary data of the frame
  * @param lastChunk   a flag whether this is the last chunk of this ID3 frame
  */
case class ProcessID3FrameData(frameHeader: ID3Header, data: ByteString,
                               lastChunk: Boolean)

/**
  * A message indicating that an ID3 frame is incomplete.
  *
  * Messages of this type are generated when an audio stream ends at an
  * unexpected position while ID3 data is still processed. In this case, the
  * processor actor has to be notified because it would otherwise wait for
  * the last chunk of data to arrive.
  *
  * @param frameHeader the header of the affected ID3v2 frame
  */
case class IncompleteID3Frame(frameHeader: ID3Header)

/**
  * A message with the metadata result extracted from an ID3v2 frame.
  *
  * A message of this type is generated by the ID3 frame processor actor
  * after an ID3 frame has been processed. It is sent to the central collector
  * actor for the current file. This actor can then store the metadata for
  * this file. Messages of this type are always generated, even if the frame
  * was invalid or processing stopped unexpectedly.
  *
  * @param header   the header of the frame
  * @param metadata an option with ID3v2 metadata
  */
case class ID3FrameMetadata(header: ID3Header, metadata: Option[MetadataProvider])

/**
  * A message with the metadata result extracted from an ID3v1 frame.
  *
  * A message of this type is generated when an MP3 file has been fully
  * processed. If the file contained an ID3v1 frame at the end, it can be
  * extracted and passed to the central collector actor. The metadata is
  * represented by an [[MetadataProvider]] object. If the audio file did not
  * contain valid ID3v1 information, this may be undefined.
  *
  * @param metadata the ID3v1 metadata
  */
case class ID3v1Metadata(metadata: Option[MetadataProvider])

/**
  * A message containing a chunk of data from an MP3 audio file.
  *
  * In order to extract some metadata (like length or ID3v1 data) from an MP3
  * audio file, the file has to be fully read. A message of this type is sent
  * for each chunk of data. It is then dispatched to all actors that need this
  * information in order to do specific processing.
  *
  * @param data a ''ByteString'' containing the chunk of data
  */
case class ProcessMp3Data(data: ByteString)

/**
  * A message with metadata extracted from an MP3 audio file.
  *
  * A message of this type is generated and passed to the metadata collector
  * actor after an MP3 audio file has been read completely. It contains all the
  * results accumulated during file processing.
  *
  * @param version        the MPEG version
  * @param layer          the audio layer version
  * @param sampleRate     the sample rate (in samples per second)
  * @param minimumBitRat  the minimum bit rate (in bps)
  * @param maximumBitRate the maximum bit rate (in bps)
  * @param duration       the duration (rounded, in milliseconds)
  */
case class Mp3Metadata(version: Int, layer: Int, sampleRate: Int, minimumBitRat: Int,
                       maximumBitRate: Int, duration: Int)

/**
  * A message requesting MP3 metadata to be sent.
  *
  * This message is sent to the actor which processes MP3 frames and calculates
  * aggregated results when an audio file has been read completely. As a
  * response, the actor sends the currently aggregated metadata back to the
  * sender.
  */
case object Mp3MetadataRequest

/**
  * A message indicating that a chunk of data from an MP3 file has been
  * processed.
  *
  * Messages of this type are generated by the MP3 data processing actor when
  * it has processed a chunk of data. The idea is that data processing can
  * happen in background while the next chunk of data is already loaded from
  * disk. With acknowledge messages of this type, back-pressure can be created
  * if processing is too slow.
  */
case object Mp3DataProcessed

/**
  * A message indicating the start of processing of an MP3 stream.
  *
  * This message is sent by the sink to the MP3 file processor actor when
  * processing starts.
  */
case object Mp3StreamInit

/**
  * A message indicating that the stream for an MP3 file has been completed.
  *
  * This means that no more processing data messages will be generated based on
  * data of the MP3 file.
  */
case object Mp3StreamCompleted

/**
  * A message indicating an error that occurred during stream processing.
  *
  * Messages of this type are sent by the stream to the processing actor when
  * a failure is detected.
  *
  * @param exception the exception causing the failure
  */
case class Mp3StreamFailure(exception: Throwable)

/**
  * A message used as confirmation that a chunk of data has been processed.
  *
  * Messages of this type are produced by the MP3 file processor actor when it
  * is ready to process another chunk of data. They are used to synchronize the
  * stream with MP3 data.
  */
case object Mp3ChunkAck
