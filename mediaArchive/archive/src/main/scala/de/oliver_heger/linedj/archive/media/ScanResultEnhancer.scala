/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.archive.media

import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}
import java.security.MessageDigest

import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.union.MediaFileUriHandler

import scala.annotation.tailrec

/**
  * An object that calculates the information required to transform a
  * [[MediaScanResult]] to an [[EnhancedMediaScanResult]].
  *
  * This object offers a function to determine ID values and URI mappings for
  * all the media contained in a ''MediaScanResult'' object.
  */
private object ScanResultEnhancer {
  /** The hash algorithm for checksum generation. */
  private val HashAlgorithm = "SHA-1"

  /** The digits that can appear in a hex string; used for conversion. */
  private val HexDigits = "0123456789ABCDEF"

  /**
    * Transforms the given ''MediaScanResult'' into an
    * ''EnhancedMediaScanResult''.
    *
    * @param result the source result
    * @return the enhanced result
    */
  def enhance(result: MediaScanResult): EnhancedMediaScanResult = {
    val init = (Map.empty[MediumID, String], Map.empty[String, FileData])
    val (checksumMapping, uriMapping) = result.mediaFiles.foldLeft(init) { (ms, e) =>
      calculateMappings(result, ms._1, ms._2, e._1, e._2)
    }
    EnhancedMediaScanResult(result, checksumMapping, uriMapping)
  }

  /**
    * Calculates the mappings for a specific medium ID and aggregates them for
    * all media.
    *
    * @param result          the scan result
    * @param checksumMapping the aggregated checksum mapping
    * @param uriMapping      the aggregated URI mapping
    * @param mid             the current medium ID
    * @param files           the files for this medium
    * @return the updated mappings
    */
  private def calculateMappings(result: MediaScanResult,
                                checksumMapping: Map[MediumID, String],
                                uriMapping: Map[String, FileData],
                                mid: MediumID,
                                files: List[FileData]):
  (Map[MediumID, String], Map[String, FileData]) = {
    val fileUris = generateUris(result.root, files)
    val relativeFileUris = mid.mediumDescriptionPath match {
      case Some(desc) =>
        val mediumPath = (Paths get desc).getParent
        generateUris(mediumPath, files)
      case None => fileUris
    }
    val checksum = calculateChecksum(relativeFileUris)
    (checksumMapping + (mid -> checksum), uriMapping ++ fileUris.toMap)
  }

  /**
    * Calculates the checksum for a medium based on the URIs for the files it
    * contains and the data objects representing these files.
    *
    * @param fileUriData data about the files and their relative URIs
    * @return the resulting checksum
    */
  private def calculateChecksum(fileUriData: Seq[(String, FileData)]): String = {
    val digest = MessageDigest getInstance HashAlgorithm
    fileUriData map { t => t._1 + ':' + t._2.size } sortWith (_ < _) foreach (u =>
      digest.update(u.getBytes(StandardCharsets.UTF_8)))
    toHexString(digest.digest())
  }

  /**
    * Transforms a sequence of ''FileData'' objects to their URIs relative to
    * the given root directory. Result is a sequence of tuples with the URI and
    * the original ''FileData'' object.
    *
    * @param root  the root path
    * @param files the files to be transformed
    * @return the sequence with URIs and original data objects
    */
  private def generateUris(root: Path, files: Seq[FileData]): Seq[(String, FileData)] =
    files.map(f => MediaFileUriHandler.generateMediaFileUri(root, Paths get f.path))
      .zip(files)

  /**
    * Converts the given byte array into a hex string representation.
    *
    * @param bytes the byte array
    * @return the resulting hex string
    */
  private def toHexString(bytes: Array[Byte]): String = {
    def toHexChar(value: Int): Char = HexDigits(value & 0x0F)

    val buf = new java.lang.StringBuilder(bytes.length * 2)

    @tailrec def convertBytes(index: Int): Unit =
      if (index < bytes.length) {
        buf.append(toHexChar(bytes(index) >>> 4)).append(toHexChar(bytes(index)))
        convertBytes(index + 1)
      }

    convertBytes(0)
    buf.toString
  }
}
