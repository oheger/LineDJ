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
