/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.media

import java.nio.file.Path
import java.util.zip.CRC32

/**
 * An internally used helper class for calculating the IDs of a medium and the
 * files it contains.
 *
 * The ID of a medium is calculated based on its content, i.e. the audio files
 * found in its directory structure. This class performs this calculation.
 * Internally, it uses a CRC32 checksum to determine a medium ID (given the
 * limited number of media, this should be sufficient). The results of this
 * class should be compatible with older versions of the MP3 player engine.
 *
 * For accessing the single files of a medium, string URIs are used derived
 * from the original paths of the files. This class generates a mapping from
 * these logic file URIs to the underlying physical ''Path'' objects. Based on
 * this information, it is possible to identify specific files in arbitrary
 * media in a unique way.
 */
private class MediumIDCalculator {
  /**
   * Calculates an alphanumeric medium ID and URIs for the files on the
   * affected medium for the specified input parameters.
   * @param mediumRoot the root directory of the medium
   * @param mediumID the ID to the medium (this information is just passed to the result object)
   * @param scanResult the associated scan result (just passed to the result object)
   * @param mediumContent the files obtained from this medium; note: the files
   *                      are expected to be relative to the medium root path
   * @return an object with the calculated IDs and URIs
   */
  def calculateMediumID(mediumRoot: Path, mediumID: MediumID, scanResult: MediaScanResult,
                        mediumContent: Seq[MediaFile]): MediumIDData = {
    val paths = mediumContent map (_.path)
    val fileURIs = paths map mediumRoot.relativize map (_.toString.replace('\\', '/'))
    val crc = new CRC32
    fileURIs sortWith (_ < _) foreach { s => crc.update(s.getBytes) }
    MediumIDData(java.lang.Long.toHexString(crc.getValue), mediumID, scanResult, Map(fileURIs zip
      mediumContent: _*))
  }
}

/**
 * A data class with information about IDs associated with a medium.
 *
 * This class stores both the global medium ID and IDs for all the files
 * contained on this medium.
 *
 * @param checksum the checksum calculated for the medium
 * @param mediumID the ID of the medium
 * @param scanResult the associated ''MediaScanResult'' object
 * @param fileURIMapping a mapping from logic file URIs to physical paths
 */
private case class MediumIDData(checksum: String, mediumID: MediumID, scanResult: MediaScanResult,
                                fileURIMapping: Map[String, MediaFile])
