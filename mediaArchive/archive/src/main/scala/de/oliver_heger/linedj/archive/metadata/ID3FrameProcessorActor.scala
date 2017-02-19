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

package de.oliver_heger.linedj.archive.metadata

import akka.actor.Actor
import de.oliver_heger.linedj.archive.mp3.ID3FrameExtractor

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
 * @param context the central extraction context object
 */
class ID3FrameProcessorActor(context: MetaDataExtractionContext) extends Actor {
  /** The extractor used by this instance. */
  private var extractor: ID3FrameExtractor = _

  override def receive: Receive = {
    case ProcessID3FrameData(path, header, data, lastChunk) =>
      if (extractor == null) {
        extractor = context createID3FrameExtractor header
      }

      extractor.addData(data, lastChunk)

      if (lastChunk) {
        context.collectorActor ! ID3FrameMetaData(path, header, extractor.createTagProvider())
      }
  }
}
