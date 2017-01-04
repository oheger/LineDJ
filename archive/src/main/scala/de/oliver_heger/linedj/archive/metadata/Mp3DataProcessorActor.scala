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

import java.nio.file.Path

import akka.actor.Actor

/**
 * An actor for processing MP3 data in order to extract meta data.
 *
 * This actor receives messages of type ''ProcessMp3Data'' and passes them to
 * an [[de.oliver_heger.splaya.mp3.Mp3DataExtractor]] object. This extractor
 * accumulates meta data. When an ''MediaFileRead'' message is received the
 * accumulated meta data is passed to the central meta data collector actor.
 *
 * @param extractionContext the meta data extraction context
 */
class Mp3DataProcessorActor(extractionContext: MetaDataExtractionContext) extends Actor {
  /** The extractor used by this instance. */
  private val extractor = extractionContext.createMp3DataExtractor()

  override def receive: Receive = {
    case ProcessMp3Data(_, data) =>
      extractor addData data

    case MediaFileRead(path) =>
      extractionContext.collectorActor ! createMetaData(path)
  }

  /**
   * Creates the meta data result based on the current state of the extractor.
   * @param path the path to the processed file
   * @return the meta data object
   */
  private def createMetaData(path: Path): Mp3MetaData = {
    Mp3MetaData(path = path, version = extractor.getVersion, layer = extractor.getLayer,
      sampleRate = extractor.getSampleRate, minimumBitRat = extractor.getMinBitRate,
      maximumBitRate = extractor.getMaxBitRate, duration = extractor.getDuration.toInt)
  }
}
