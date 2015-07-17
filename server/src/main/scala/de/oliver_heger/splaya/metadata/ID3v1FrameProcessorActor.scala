/*
 * Copyright 2015 The Developers Team.
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
package de.oliver_heger.splaya.metadata

import akka.actor.Actor

/**
 * An actor class responsible for the processing of MP3 chunk data in order to
 * extract ID3v1 meta data.
 *
 * This actor receives messages of type ''ProcessMp3Data''. Each message is
 * passed to an [[de.oliver_heger.splaya.mp3.ID3v1Extractor]] object obtained
 * from the extraction context. When a message is received indicating that the
 * MP3 file has been read completely the results from the extractor are
 * obtained and sent to the central meta data collector actor.
 *
 * @param extractionContext the meta data extraction context
 */
class ID3v1FrameProcessorActor(extractionContext: MetaDataExtractionContext) extends Actor {
  /** The extractor used by this object. */
  private val extractor = extractionContext.createID3v1Extractor()

  override def receive: Receive = {
    case ProcessMp3Data(_, source) =>
      extractor addData source

    case MediaFileRead(path) =>
      extractionContext.collectorActor ! ID3v1MetaData(path, extractor.createTagProvider())
  }
}
