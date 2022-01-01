/*
 * Copyright 2015-2022 The Developers Team.
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

import akka.actor.Actor
import de.oliver_heger.linedj.extract.id3.model._

/**
  * An actor for processing MP3 data in order to extract meta data.
  *
  * This actor receives messages of type ''ProcessMp3Data'' and passes them to
  * an [[de.oliver_heger.linedj.extract.id3.model.Mp3DataExtractor]] object.
  * This extractor accumulates meta data. When an ''Mp3MetaDataRequest''
  * message is received the accumulated meta data is passed to the central
  * meta data collector actor.
  *
  * @param extractor the meta data extractor object (the default constructor
  *                  creates a default extractor)
  */
class Mp3DataProcessorActor(private[processor] val extractor: Mp3DataExtractor) extends Actor {
  def this() = this(new Mp3DataExtractor)

  override def receive: Receive = {
    case ProcessMp3Data(data) =>
      extractor addData data
      sender() ! Mp3DataProcessed

    case Mp3MetaDataRequest =>
      sender() ! createMetaData()
  }

  /**
    * Creates the meta data result based on the current state of the extractor.
    *
    * @return the meta data object
    */
  private def createMetaData(): Mp3MetaData = {
    Mp3MetaData(version = extractor.getVersion, layer = extractor.getLayer,
      sampleRate = extractor.getSampleRate, minimumBitRat = extractor.getMinBitRate,
      maximumBitRate = extractor.getMaxBitRate, duration = extractor.getDuration.toInt)
  }
}
