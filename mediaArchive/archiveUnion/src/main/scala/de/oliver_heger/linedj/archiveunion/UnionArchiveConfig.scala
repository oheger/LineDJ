/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.archiveunion

object UnionArchiveConfig:
  /** Constant for the common prefix for configuration options. */
  private val ConfigPrefix = "media."

  /** Constant for the prefix for the media archive configuration. */
  private val MediaArchivePrefix = ConfigPrefix + "mediaArchive."

  /** The configuration property for the size of metadata update chunks. */
  final val PropMetadataUpdateChunkSize = MediaArchivePrefix + "metaDataUpdateChunkSize"

  /** The configuration property for the maximum metadata message size. */
  final val PropMetadataMaxMessageSize = MediaArchivePrefix + "metaDataMaxMessageSize"

/**
  * A class for managing configuration data for the union media archive.
  *
  * This class manages some properties accessed by the actors comprising the
  * union media archive. The companion object defines a method which allows
  * creating instances from a ''Configuration'' object.
  *
  * An instance is created when the union archive component is started.
  *
  * @param metadataUpdateChunkSize the size of a chunk of metadata sent to a
  *                                registered metadata listener as an update
  *                                notification; this property determines how
  *                                often a metadata listener receives update
  *                                notifications when new metadata becomes
  *                                available
  * @param initMetadataMaxMsgSize  the maximum number of entries in a metadata
  *                                chunk message; there is a limit in the size
  *                                of remoting messages; therefore, this
  *                                parameter is important to not exceed this
  *                                limit; this value should be a multiple of the
  *                                update chunk size
  */
case class UnionArchiveConfig(metadataUpdateChunkSize: Int,
                              initMetadataMaxMsgSize: Int):
  /** The maximum size of metadata chunk messages. */
  val metadataMaxMessageSize: Int = calcMaxMessageSize()

  /**
    * Calculates the maximum message size based on constructor parameters. This
    * method ensures that the maximum message size is always a multiple of the
    * update chunk size. If necessary, the value is rounded upwards.
    *
    * @return the maximum metadata chunk message size
    */
  private def calcMaxMessageSize(): Int =
    if initMetadataMaxMsgSize % metadataUpdateChunkSize == 0 then initMetadataMaxMsgSize
    else (initMetadataMaxMsgSize / metadataUpdateChunkSize + 1) * metadataUpdateChunkSize
