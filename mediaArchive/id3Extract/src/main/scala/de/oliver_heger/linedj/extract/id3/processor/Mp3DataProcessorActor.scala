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

import de.oliver_heger.linedj.extract.id3.model._
import org.apache.pekko.actor.Actor

/**
  * An actor for processing MP3 data in order to extract metadata.
  *
  * This actor receives messages of type ''ProcessMp3Data'' and passes them to
  * an [[de.oliver_heger.linedj.extract.id3.model.Mp3DataExtractor]] object.
  * This extractor accumulates metadata. When an ''Mp3MetaDataRequest''
  * message is received the accumulated metadata is passed to the central
  * metadata collector actor.
  *
  * @param extractor the metadata extractor object (the default constructor
  *                  creates a default extractor)
  */
class Mp3DataProcessorActor(private[processor] val extractor: Mp3DataExtractor) extends Actor:
  def this() = this(new Mp3DataExtractor)

  override def receive: Receive =
    case ProcessMp3Data(data) =>
      extractor addData data
      sender() ! Mp3DataProcessed

    case Mp3MetadataRequest =>
      sender() ! createMetadata()

  /**
    * Creates the metadata result based on the current state of the extractor.
    *
    * @return the metadata object
    */
  private def createMetadata(): Mp3Metadata =
    Mp3Metadata(version = extractor.getVersion, layer = extractor.getLayer,
      sampleRate = extractor.getSampleRate, minimumBitRat = extractor.getMinBitRate,
      maximumBitRate = extractor.getMaxBitRate, duration = extractor.getDuration.toInt)
