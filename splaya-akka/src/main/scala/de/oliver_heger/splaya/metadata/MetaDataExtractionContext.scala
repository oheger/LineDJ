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

import akka.actor.ActorRef
import de.oliver_heger.splaya.mp3._

/**
 * A helper class providing central information and functionality required
 * during the extraction of meta data from audio files.
 *
 * In order to access a larger library of audio files in a comfortable way,
 * meta data about the files (like title, interpret, album, etc.) must be made
 * available. Therefore, after the directories containing media files have been
 * scanned, the detected files are processed to extract the relevant meta data
 * they contain. This is a complex and parallel process involving a bunch of
 * actors.
 *
 * This class contains some data used by many actors taking part in the
 * extraction process. For instance, it allows creating various kinds of
 * extractor objects for meta data. Further, the reference to the actor
 * collecting the meta data found can be queried.
 *
 * @param collectorActor the actor collecting all kinds of meta data results
 * @param id3TagSizeLimit an optional size limit for ID3v2 tags; larger tags are ignored
 */
private class MetaDataExtractionContext(val collectorActor: ActorRef, val id3TagSizeLimit: Int =
Integer.MAX_VALUE) {
  /** The object for extracting ID3 headers. */
  val headerExtractor = new ID3HeaderExtractor

  /**
   * Creates a new ''ID3FrameExtractor'' object for the specified header.
   * @param header the frame header
   * @return a corresponding new ''ID3FrameExtractor''
   */
  def createID3FrameExtractor(header: ID3Header): ID3FrameExtractor =
    new ID3FrameExtractor(header, id3TagSizeLimit)

  /**
   * Creates a new ''Mp3DataExtractor'' object.
   * @return the newly created ''Mp3DataExtractor''
   */
  def createMp3DataExtractor(): Mp3DataExtractor = new Mp3DataExtractor

  /**
   * Creates a new ''ID3v1Extractor'' object.
   * @return the newly created ''ID3v1Extractor''
   */
  def createID3v1Extractor(): ID3v1Extractor = new ID3v1Extractor
}
