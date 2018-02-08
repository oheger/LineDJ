/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.archivehttpstart

import de.oliver_heger.linedj.archivecommon.download.DownloadConfig
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import org.apache.commons.configuration.Configuration

/**
  * A helper class for generating configuration data for the archive startup
  * application.
  *
  * This functionality is required by multiple test classes. Therefore, it has
  * been extracted into a separate test helper class.
  */
object StartupConfigTestHelper {
  /** The prefix key for configuration settings about archives. */
  val KeyArchives = "media.archives.archive"

  /** Convenience constant for generate config key prefixes. */
  private val KeyPrefix = KeyArchives + "."

  /** The test chunk size for download operations. */
  val DownloadChunkSize = 8888

  /**
    * Generates the name of a test archive.
    *
    * @param idx the index of the archive
    * @return the name of this archive
    */
  def archiveName(idx: Int): String = "Http Archive Test Name " + idx

  /**
    * Generates the URI of a test archive.
    *
    * @param idx the index of the archive
    * @return the URI of this archive
    */
  def archiveUri(idx: Int): String = s"https://test-archive$idx.org/index.json"

  /**
    * Generates the name of a realm based on an index.
    *
    * @param idx the index of the test realm
    * @return a name for this realm
    */
  def realmName(idx: Int): String = "realm_" + idx

  /**
    * Generates a short name for an HTTP archive.
    *
    * @param idx the index of the archive
    * @return the short name
    */
  def shortName(idx: Int): String = {
    val prefix = "Http+Archive+Tes"
    if (idx > 1) prefix + (idx - 1) else prefix
  }

  /**
    * Adds properties for a test HTTP archive to the specified configuration.
    *
    * @param c     the configuration
    * @param idx   the index of the test archive
    * @param realm option for the name of the realm
    * @return the updated configuration
    */
  def addArchiveToConfig(c: Configuration, idx: Int, realm: Option[String] = None):
  Configuration = {
    c.addProperty(KeyArchives + "(-1)." + HttpArchiveConfig.PropArchiveName, archiveName(idx))
    c.addProperty(KeyPrefix + HttpArchiveConfig.PropArchiveUri, archiveUri(idx))
    c.addProperty(KeyPrefix + HttpArchiveConfig.PropDownloadBufferSize, 16384)
    c.addProperty(KeyPrefix + HttpArchiveConfig.PropDownloadMaxInactivity, 5 * 60)
    c.addProperty(KeyPrefix + HttpArchiveConfig.PropTimeoutReadSize, 128 * 1024)
    c.addProperty(DownloadConfig.PropDownloadChunkSize, DownloadChunkSize)
    c.addProperty(KeyPrefix + "realm", realm.getOrElse(realmName(idx)))
    c
  }

  /**
    * Adds a number of properties for a set of HTTP archives to the given
    * configuration. For each archive in the specified range test properties
    * are added.
    *
    * @param c       the configuration
    * @param fromIdx the start index
    * @param toIdx   the end index (inclusive)
    * @return the test configuration
    */
  def addConfigs(c: Configuration, fromIdx: Int, toIdx: Int): Configuration =
    (fromIdx to toIdx).foldLeft(c)((c, i) => addArchiveToConfig(c, i))
}
