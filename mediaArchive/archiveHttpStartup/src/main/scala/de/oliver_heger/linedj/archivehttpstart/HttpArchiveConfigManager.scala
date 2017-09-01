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

package de.oliver_heger.linedj.archivehttpstart

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import de.oliver_heger.linedj.archivecommon.download.DownloadConfig
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import org.apache.commons.configuration.Configuration

import scala.annotation.tailrec
import scala.collection.immutable.{SortedMap, TreeMap}

/**
  * A data class collecting information about a single HTTP archive to be
  * started up.
  *
  * @param config    the configuration of the archive
  * @param realm     the realm to be used for login
  * @param shortName a unique short name for this archive
  */
private case class HttpArchiveData(config: HttpArchiveConfig, realm: String,
                                   shortName: String)

private object HttpArchiveConfigManager {
  /** The base key for accessing archives from the configuration. */
  private val KeyArchives = "media.archives.archive"

  /** The key for querying archive names. */
  private val KeyArchiveNames = KeyArchives + ".archiveName"

  /** The key for querying the realm of an archive. */
  private val KeyRealm = ".realm"

  /** The maximum length of a short name for an archive. */
  private val LengthShortName = 16

  /**
    * Returns a new instance of ''HttpArchiveConfigManager'' that has been
    * extracted from the specified configuration.
    *
    * @param c the configuration
    * @return the manager initialized from the configuration
    */
  def apply(c: Configuration): HttpArchiveConfigManager = {
    val archiveNames = c.getList(KeyArchiveNames)
    val maxIdx = archiveNames.size() - 1
    val downloadConfig = DownloadConfig(c)
    new HttpArchiveConfigManager(addArchive(c, downloadConfig, maxIdx, TreeMap.empty, Set.empty))
  }

  /**
    * Iterates over the configuration data for all defined archives and
    * constructs a map which stores all extracted information.
    *
    * @param c              the configuration
    * @param downloadConfig the download configuration
    * @param idx            the index of the archive to be processed
    * @param data           the map with the data so far extracted
    * @param names          a set with the short names so far generated
    * @return a map with data about all archives
    */
  @tailrec private def addArchive(c: Configuration, downloadConfig: DownloadConfig, idx: Int,
                                  data: SortedMap[String, HttpArchiveData], names: Set[String]):
  SortedMap[String, HttpArchiveData] =
  if (idx < 0) data
  else {
    val currentKey = KeyArchives + s"($idx)"
    val optNextData = createArchiveData(c, downloadConfig, currentKey, names) map (d =>
      (data + (d.config.archiveName -> d), names + d.shortName))
    val nextData = optNextData getOrElse(data, names)
    addArchive(c, downloadConfig, idx - 1, nextData._1, nextData._2)
  }

  /**
    * Tries to create an ''HttpArchiveData'' object for a specific HTTP
    * archive. If this is not possible - because the configuration data for
    * this archive is incomplete -, result is ''None''.
    *
    * @param c              the configuration
    * @param downloadConfig the download configuration
    * @param currentKey     the current configuration key
    * @param names          a set with the short names already in use
    * @return an ''Option'' for the data object created
    */
  private def createArchiveData(c: Configuration, downloadConfig: DownloadConfig,
                                currentKey: String, names: Set[String]):
  Option[HttpArchiveData] =
    for {config <- HttpArchiveConfig(c, currentKey, null, downloadConfig).toOption
         realm <- Option(c.getString(currentKey + KeyRealm))
    } yield HttpArchiveData(config, realm, generateShortName(config, names))

  /**
    * Generates a short name for an archive. Makes sure that the name is unique
    * and valid to be used as an actor name.
    *
    * @param config the archive configuration
    * @param names  a set with the names already in use
    * @return the short name for this archive
    */
  private def generateShortName(config: HttpArchiveConfig, names: Set[String]): String = {
    val prefix = URLEncoder.encode(extractShortName(config),
      StandardCharsets.UTF_8.name())

    @tailrec def generateUniqueName(idx: Int): String = {
      val name = if (idx > 1) prefix + (idx - 1) else prefix
      if (!names.contains(name)) name
      else generateUniqueName(idx + 1)
    }

    generateUniqueName(1)
  }

  /**
    * Extracts the short name from the archive name applying a length
    * restriction if necessary.
    *
    * @param config the archive configuration
    * @return the short name derived from the archive name
    */
  private def extractShortName(config: HttpArchiveConfig): String =
    if (config.archiveName.length <= LengthShortName) config.archiveName
    else config.archiveName.substring(0, LengthShortName)
}

/**
  * An internally used helper class that is responsible for the management of
  * the configurations of the HTTP archives to be started.
  *
  * An instance provides convenient access to the configuration of the startup
  * application which is related to HTTP archives. The following data is
  * available:
  *  - A list with the names of the declared HTTP archives. Access to the
  * detail information of an archive is then possible by this name.
  *  - For each HTTP archive its
  * [[de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig]].
  *  - For each HTTP archive a ''realm'' with user credentials. This is needed
  * to log into the archive on a remote HTTP server.
  *  - For each HTTP archive a short name derived from its name. Based on this
  * name, the names of the actors managing this archive are constructed. This
  * class ensures that such names are unique.
  *
  * @param archives a map with data about the managed archives; key is the
  *                 archive name (the map is sorted by archive names)
  */
private case class HttpArchiveConfigManager(archives: SortedMap[String, HttpArchiveData]) {
  /**
    * Returns a collection with data about archives that belong to the
    * specified realm.
    *
    * @param realm the name of the desired realm
    * @return a collection with data about archives belonging to this realm
    */
  def archivesForRealm(realm: String): Iterable[HttpArchiveData] =
    archives.values.filter(_.realm == realm)
}
