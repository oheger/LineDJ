/*
 * Copyright 2015-2023 The Developers Team.
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

import de.oliver_heger.linedj.extract.id3.model._
import org.apache.pekko.actor.{Actor, ActorRef}

/**
  * An actor class responsible for the processing of ID3v2 frames.
  *
  * Actors of this type handle [[ProcessID3FrameData]] messages with the help
  * of an extractor for ID3v2 frames. The data read for the current frame is
  * fed into the extractor. When the frame has been fully read the results of
  * the extraction process are sent to the central meta data collector actor.
  *
  * A single actor instance can process exactly one ID3v2 frame, either in a
  * single shot or in multiple chunks. Afterwards it should be stopped.
  *
  * @param collector the actor collecting processing results
  * @param extractor the extractor for ID3 frame data
  */
class ID3FrameProcessorActor(collector: ActorRef, extractor: ID3FrameExtractor) extends Actor:
  override def receive: Receive =
    case ProcessID3FrameData(header, data, lastChunk) if checkHeader(header) =>
      extractor.addData(data, lastChunk)
      if lastChunk then
        collector ! ID3FrameMetaData(header, extractor.createTagProvider())

    case IncompleteID3Frame(header) if checkHeader(header) =>
      collector ! ID3FrameMetaData(header, extractor.createTagProvider())

  /**
    * Checks whether the passed in header is the expected one.
    *
    * @param header the header to be checked
    * @return a flag whether this header is valid
    */
  private def checkHeader(header: ID3Header): Boolean =
    header == extractor.header
