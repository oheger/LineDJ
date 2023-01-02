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

import de.oliver_heger.linedj.extract.metadata.MetaDataProvider
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData

/**
  * An internally used helper class for collecting different parts of meta data
  * for media files.
  *
  * When a media file is processed to extract meta data for it several steps are
  * done in parallel, e.g. processing of ID3v2 tags, processing of Mpeg frames,
  * etc. The results of these steps become available in no defined order, and
  * the meta data for this file is complete only if all outstanding processing
  * results have been received.
  *
  * This class keeps track of the meta data parts expected for a specific media
  * file. It allows adding parts when they become available and checking whether
  * all parts are there now. If this is the case, a final meta data object can
  * be extracted from all the parts collected during processing.
  *
  * A media file can contain redundant meta data. For instance, meta data can be
  * provided as ID3v2 tags (in different versions) and as ID3v1 tags. This class
  * organizes such potential redundant information based on the ID3 version:
  * tags with higher versions are checked first; if they contain data, this data
  * is returned. Otherwise, search continues with meta data from lower versions.
  * This is handled by an [[MetaDataID3Collector]] used by this class.
  *
  * @param file         the ''FileData'' for which meta data is collected
  * @param id3Collector the helper object for collecting ID3 data
  */
private class MetaDataPartsCollector(val file: FileData,
                                     private[processor] val id3Collector: MetaDataID3Collector) {
  /** Stores MP3-related meta data when it arrives. */
  private var mp3MetaData: Option[Mp3MetaData] = None

  /**
    * A set for the versions of ID3 frames which are outstanding. The set is
    * initialized with version 1 for ID3v1 meta data which is always expected.
    * ID3 data for higher versions is announced when it is detected.
    */
  private var outstandingID3Data = Set(1)

  /**
    * Default constructor. Creates default helper objects.
    *
    * @param file the ''FileData'' for which meta data is collected
    */
  def this(file: FileData) = this(file, new MetaDataID3Collector)

  /**
    * Sets MP3-related meta data extracted from an audio file and returns an
    * option with the final meta data. The option is defined if now all data is
    * available.
    *
    * @param mp3Data MP3-related meta data
    * @return an option for the resulting meta data
    */
  def setMp3MetaData(mp3Data: Mp3MetaData): Option[MediaMetaData] = {
    mp3MetaData = Some(mp3Data)
    createFinalMetaDataIfComplete()
  }

  /**
    * Sets meta data for ID3 version 1. This method is called for each media
    * file that has been processed. Therefore, exactly one invocation of this
    * method is expected before the final meta data can be generated.
    *
    * @param provider the provider for ID3v1 information
    * @return an option for the resulting meta data
    */
  def setID3v1MetaData(provider: Option[MetaDataProvider]): Option[MediaMetaData] =
    id3MetaDataAdded(1, provider)

  /**
    * Notifies this object that an ID3 frame has been detected which is now
    * processed. Final meta data cannot be generated before the results of
    * this processing are available. It may be possible that this method is
    * invoked multiple times for the same version. This happens if an ID3
    * frame is so big that it has to be processed in multiple chunks. In this
    * case, it is counted only once.
    *
    * @param version the version of the ID3 frame
    */
  def expectID3Data(version: Int): Unit = {
    outstandingID3Data += version
  }

  /**
    * Notifies this object that processing of an ID3 frame has ended. The
    * results of the processing are passed. If now all data is available, the
    * final meta data can be generated.
    *
    * @param id3Data ID3 meta data
    * @return an option for the resulting meta data
    */
  def addID3Data(id3Data: ID3FrameMetaData): Option[MediaMetaData] =
    id3MetaDataAdded(id3Data.header.version, id3Data.metaData)

  /**
    * Handles the addition of ID3 meta data. If defined, the data is added to
    * the ID3 collector. If now all data is available, resulting meta data is
    * generated.
    *
    * @param version the ID3 version
    * @param data    the optional data
    * @return an option for the resulting meta data
    */
  private def id3MetaDataAdded(version: Int, data: Option[MetaDataProvider]): Option[MediaMetaData]
  = {
    data foreach (id3Collector.addProvider(version, _))
    outstandingID3Data -= version
    createFinalMetaDataIfComplete()
  }

  /**
    * Returns an option with the final meta data. If all required data is
    * already available, meta data can be created now. Otherwise, result is
    * ''None''.
    *
    * @return an option for the resulting meta data
    */
  private def createFinalMetaDataIfComplete(): Option[MediaMetaData] = {
    mp3MetaData match {
      case Some(data) if outstandingID3Data.isEmpty =>
        val combinedID3TagProvider = id3Collector.createCombinedID3TagProvider()
        Some(MediaMetaData(title = combinedID3TagProvider.title, artist = combinedID3TagProvider
          .artist, album = combinedID3TagProvider.album, inceptionYear = combinedID3TagProvider
          .inceptionYear, trackNumber = combinedID3TagProvider.trackNo, duration = Some(data
          .duration), formatDescription = Some(generateFormatDescription(data)),
          size = file.size))

      case _ => None
    }
  }

  /**
    * Generates a format description for an MP3 audio file based on the given
    * meta data. A UI may choose to display this string.
    *
    * @param data the meta data for the MP3 file
    * @return the format description
    */
  private def generateFormatDescription(data: Mp3MetaData): String = {
    s"${data.maximumBitRate / 1000} kbps"
  }
}
