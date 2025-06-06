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

import de.oliver_heger.linedj.extract.metadata.MetadataProvider
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata

/**
  * An internally used helper class for collecting different parts of metadata
  * for media files.
  *
  * When a media file is processed to extract metadata for it several steps are
  * done in parallel, e.g. processing of ID3v2 tags, processing of Mpeg frames,
  * etc. The results of these steps become available in no defined order, and
  * the metadata for this file is complete only if all outstanding processing
  * results have been received.
  *
  * This class keeps track of the metadata parts expected for a specific media
  * file. It allows adding parts when they become available and checking whether
  * all parts are there now. If this is the case, a final metadata object can
  * be extracted from all the parts collected during processing.
  *
  * A media file can contain redundant metadata. For instance, metadata can be
  * provided as ID3v2 tags (in different versions) and as ID3v1 tags. This class
  * organizes such potential redundant information based on the ID3 version:
  * tags with higher versions are checked first; if they contain data, this data
  * is returned. Otherwise, search continues with metadata from lower versions.
  * This is handled by an [[MetadataID3Collector]] used by this class.
  *
  * @param file         the ''FileData'' for which metadata is collected
  * @param id3Collector the helper object for collecting ID3 data
  */
private class MetadataPartsCollector(val file: FileData,
                                     private[processor] val id3Collector: MetadataID3Collector):
  /** Stores MP3-related metadata when it arrives. */
  private var mp3Metadata: Option[Mp3Metadata] = None

  /**
    * A set for the versions of ID3 frames which are outstanding. The set is
    * initialized with version 1 for ID3v1 metadata which is always expected.
    * ID3 data for higher versions is announced when it is detected.
    */
  private var outstandingID3Data = Set(1)

  /**
    * Default constructor. Creates default helper objects.
    *
    * @param file the ''FileData'' for which metadata is collected
    */
  def this(file: FileData) = this(file, new MetadataID3Collector)

  /**
    * Sets MP3-related metadata extracted from an audio file and returns an
    * option with the final metadata. The option is defined if now all data is
    * available.
    *
    * @param mp3Data MP3-related metadata
    * @return an option for the resulting metadata
    */
  def setMp3Metadata(mp3Data: Mp3Metadata): Option[MediaMetadata] =
    mp3Metadata = Some(mp3Data)
    createFinalMetadataIfComplete()

  /**
    * Sets metadata for ID3 version 1. This method is called for each media
    * file that has been processed. Therefore, exactly one invocation of this
    * method is expected before the final metadata can be generated.
    *
    * @param provider the provider for ID3v1 information
    * @return an option for the resulting metadata
    */
  def setID3v1Metadata(provider: Option[MetadataProvider]): Option[MediaMetadata] =
    id3MetadataAdded(1, provider)

  /**
    * Notifies this object that an ID3 frame has been detected which is now
    * processed. Final metadata cannot be generated before the results of
    * this processing are available. It may be possible that this method is
    * invoked multiple times for the same version. This happens if an ID3
    * frame is so big that it has to be processed in multiple chunks. In this
    * case, it is counted only once.
    *
    * @param version the version of the ID3 frame
    */
  def expectID3Data(version: Int): Unit =
    outstandingID3Data += version

  /**
    * Notifies this object that processing of an ID3 frame has ended. The
    * results of the processing are passed. If now all data is available, the
    * final metadata can be generated.
    *
    * @param id3Data ID3 metadata
    * @return an option for the resulting metadata
    */
  def addID3Data(id3Data: ID3FrameMetadata): Option[MediaMetadata] =
    id3MetadataAdded(id3Data.header.version, id3Data.metadata)

  /**
    * Handles the addition of ID3 metadata. If defined, the data is added to
    * the ID3 collector. If now all data is available, resulting metadata is
    * generated.
    *
    * @param version the ID3 version
    * @param data    the optional data
    * @return an option for the resulting metadata
    */
  private def id3MetadataAdded(version: Int, data: Option[MetadataProvider]): Option[MediaMetadata] =
    data foreach (id3Collector.addProvider(version, _))
    outstandingID3Data -= version
    createFinalMetadataIfComplete()

  /**
    * Returns an option with the final metadata. If all required data is
    * already available, metadata can be created now. Otherwise, result is
    * ''None''.
    *
    * @return an option for the resulting metadata
    */
  private def createFinalMetadataIfComplete(): Option[MediaMetadata] =
    mp3Metadata match
      case Some(data) if outstandingID3Data.isEmpty =>
        val combinedID3TagProvider = id3Collector.createCombinedID3TagProvider()
        Some(MediaMetadata(title = combinedID3TagProvider.title, artist = combinedID3TagProvider
          .artist, album = combinedID3TagProvider.album, inceptionYear = combinedID3TagProvider
          .inceptionYear, trackNumber = combinedID3TagProvider.trackNo, duration = Some(data
          .duration), formatDescription = Some(generateFormatDescription(data)),
          size = Some(file.size.toInt)))

      case _ => None

  /**
    * Generates a format description for an MP3 audio file based on the given
    * metadata. A UI may choose to display this string.
    *
    * @param data the metadata for the MP3 file
    * @return the format description
    */
  private def generateFormatDescription(data: Mp3Metadata): String =
    s"${data.maximumBitRate / 1000} kbps"
