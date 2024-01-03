/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.archivehttpstart.app

import de.oliver_heger.linedj.archivecommon.download.DownloadConfig
import org.apache.commons.configuration.Configuration
import org.apache.commons.logging.LogFactory

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import scala.annotation.tailrec
import scala.collection.immutable.{SortedMap, TreeMap}
import scala.util.{Failure, Success, Try}

private object HttpArchiveConfigManager:
  /**
    * The default name of the HTTP protocol associated with an archive. This
    * name is used for the protocol if the configuration of an archive does not
    * explicitly specify a protocol.
    */
  val DefaultProtocolName = "webdav"

  /** Constant for the realm type ''basic auth''. */
  val RealmTypeBasicAuth = "basic"

  /** Constant for the realm type ''OAuth''. */
  val RealmTypeOAuth = "oauth"

  /** Name of the section that contains all relevant properties. */
  private val SectionMedia = "media"

  /** Name of the section with properties for the media archive. */
  private val SectionMediaArchive = SectionMedia + ".mediaArchive"

  /** The base key for accessing archives from the configuration. */
  private val KeyArchives = SectionMedia + ".archives.archive"

  /** The key for querying archive names. */
  private val KeyArchiveNames = KeyArchives + ".archiveName"

  /** The key for querying the realm of an archive. */
  private val KeyRealm = ".realm"

  /** The key for querying the protocol of an archive. */
  private val KeyProtocol = ".protocol"

  /** The key for the encrypted flag of an archive. */
  private val KeyEncrypted = ".encrypted"

  /** The key under which information about realms is stored. */
  private val KeyRealms = "media.realms.realm"

  /** The key for the name of a realm. */
  private val KeyRealmName = ".name"

  /** The key for the type of a realm. */
  private val KeyRealmType = ".type"

  /** The key for the path of an OAuth realm. */
  private val KeyOAuthRealmPath = ".path"

  /** The key for the IDP name of an OAuth realm. */
  private val KeyOAuthRealmIdp = ".idp"

  /** The maximum length of a short name for an archive. */
  private val LengthShortName = 16

  /** The logger. */
  private val log = LogFactory.getLog(classOf[HttpArchiveConfigManager])

  /**
    * A special exception class for reporting problems with the archive
    * configuration.
    *
    * @param msg the error message
    */
  class ArchiveConfigException(msg: String) extends Exception(msg)

  /**
    * Returns a new instance of ''HttpArchiveConfigManager'' that has been
    * extracted from the specified configuration.
    *
    * @param c the configuration
    * @return the manager initialized from the configuration
    */
  def apply(c: Configuration): HttpArchiveConfigManager =
    val realmNames = c.getList(KeyRealms + KeyRealmName)
    val realms = extractRealms(c, realmNames.size() - 1, Map.empty)
    val archiveNames = c.getList(KeyArchiveNames)
    val downloadConfig = DownloadConfig(c.subset(SectionMediaArchive))
    new HttpArchiveConfigManager(addArchive(c, downloadConfig, realms, archiveNames.size() - 1,
      TreeMap.empty, Set.empty))

  /**
    * Iterates over the configuration data for all defined archives and
    * constructs a map which stores all extracted information.
    *
    * @param c              the configuration
    * @param downloadConfig the download configuration
    * @param realms         a map with all known realms
    * @param idx            the index of the archive to be processed
    * @param data           the map with the data so far extracted
    * @param names          a set with the short names so far generated
    * @return a map with data about all archives
    */
  @tailrec private def addArchive(c: Configuration, downloadConfig: DownloadConfig,
                                  realms: Map[String, Try[ArchiveRealm]], idx: Int,
                                  data: SortedMap[String, HttpArchiveData], names: Set[String]):
  SortedMap[String, HttpArchiveData] =
    if idx < 0 then data
    else
      val currentKey = KeyArchives + s"($idx)"
      val optNextData = createArchiveData(c, downloadConfig, realms, currentKey, names) map (d =>
        (data + (d.config.archiveConfig.archiveName -> d), names + d.shortName))
      val nextData = optNextData getOrElse(data, names)
      addArchive(c, downloadConfig, realms, idx - 1, nextData._1, nextData._2)

  /**
    * Tries to create an ''HttpArchiveData'' object for a specific HTTP
    * archive. If this is not possible - because the configuration data for
    * this archive is incomplete -, result is ''None''.
    *
    * @param c              the configuration
    * @param downloadConfig the download configuration
    * @param realms         a map with all known realms
    * @param currentKey     the current configuration key
    * @param names          a set with the short names already in use
    * @return an ''Option'' for the data object created
    */
  private def createArchiveData(c: Configuration, downloadConfig: DownloadConfig,
                                realms: Map[String, Try[ArchiveRealm]],
                                currentKey: String, names: Set[String]):
  Option[HttpArchiveData] =
    for config <- HttpArchiveStartupConfig(c, currentKey, downloadConfig).toOption
         realmName <- Option(c.getString(currentKey + KeyRealm))
         realm <- realmForArchive(realms, realmName, config)
         yield HttpArchiveData(config, realm, generateShortName(config, names),
      encrypted = c.getBoolean(currentKey + KeyEncrypted, false),
      protocol = c.getString(currentKey + KeyProtocol, DefaultProtocolName))

  /**
    * Generates a short name for an archive. Makes sure that the name is unique
    * and valid to be used as an actor name.
    *
    * @param config the archive configuration
    * @param names  a set with the names already in use
    * @return the short name for this archive
    */
  private def generateShortName(config: HttpArchiveStartupConfig, names: Set[String]): String =
    val prefix = URLEncoder.encode(extractShortName(config),
      StandardCharsets.UTF_8.name())

    @tailrec def generateUniqueName(idx: Int): String =
      val name = if idx > 1 then prefix + (idx - 1) else prefix
      if !names.contains(name) then name
      else generateUniqueName(idx + 1)

    generateUniqueName(1)

  /**
    * Extracts the short name from the archive name applying a length
    * restriction if necessary.
    *
    * @param config the archive configuration
    * @return the short name derived from the archive name
    */
  private def extractShortName(config: HttpArchiveStartupConfig): String =
    if config.archiveConfig.archiveName.length <= LengthShortName then config.archiveConfig.archiveName
    else config.archiveConfig.archiveName.substring(0, LengthShortName)

  /**
    * Returns an ''Option'' with the realm to be used for an archive. This
    * method looks up the realm identified by the given name in the map of
    * predefined realms. If the realm happens to be invalid, result is
    * ''None'', causing the archive to be filtered out. If the realm does not
    * exist, a default ''BasicAuthRealm'' is created.
    *
    * @param realms    the map with known realms
    * @param realmName the name of the desired realm
    * @return an ''Option'' with the realm to be used
    */
  private def realmForArchive(realms: Map[String, Try[ArchiveRealm]], realmName: String,
                              archiveConfig: HttpArchiveStartupConfig): Option[ArchiveRealm] =
    realms.getOrElse(realmName, Success(BasicAuthRealm(realmName))) match
      case Success(realm) =>
        Some(realm)
      case Failure(ex) =>
        log.error(s"Could not create archive for ${archiveConfig.archiveConfig.archiveBaseUri}. " +
          "It references an invalid realm.", ex)
        None

  /**
    * Iterates over the configuration properties that declare realm objects and
    * creates the corresponding representations. Result is a map with realm
    * names as keys and ''Try'' objects with the actual realm data. Archives
    * referencing realms that could not be extracted are sorted out.
    *
    * @param c      the configuration
    * @param idx    the index of the realm to be extracted
    * @param realms the map with realm data to be populated
    * @return the map with all extracted realms
    */
  @tailrec private def extractRealms(c: Configuration, idx: Int, realms: Map[String, Try[ArchiveRealm]]):
  Map[String, Try[ArchiveRealm]] =
    if idx < 0 then realms
    else
      val currentKey = s"$KeyRealms($idx)"
      val tRealm = createRealm(c, currentKey)
      extractRealms(c, idx - 1, realms + tRealm)

  /**
    * Extracts information about a realm from the configuration. This can fail
    * if mandatory properties are missing. Therefore, result is a tuple with
    * the realm name (which is assumed to be present) and a ''Try'' with the
    * actual realm data.
    *
    * @param c          the configuration
    * @param currentKey the base key
    * @return a tuple with the realm name and a ''Try'' with the realm data
    */
  private def createRealm(c: Configuration, currentKey: String): (String, Try[ArchiveRealm]) =
    val name = c.getString(currentKey + KeyRealmName)

    def mandatoryProperty(key: String): String =
      val value = c.getString(currentKey + key)
      if value == null then
        throw new ArchiveConfigException(s"Missing mandatory property $key for realm $name.")
      value

    val realm = Try:
      val archiveType = c.getString(currentKey + KeyRealmType)
      archiveType match
        case RealmTypeBasicAuth =>
          BasicAuthRealm(name)
        case RealmTypeOAuth =>
          OAuthRealm(name,
            Paths.get(mandatoryProperty(KeyOAuthRealmPath)),
            mandatoryProperty(KeyOAuthRealmIdp))
        case t =>
          throw new ArchiveConfigException(s"Unknown archive type: $t.")
    (name, realm)

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
private case class HttpArchiveConfigManager(archives: SortedMap[String, HttpArchiveData]):
  /**
    * Returns a collection with data about archives that belong to the
    * specified realm.
    *
    * @param realm the name of the desired realm
    * @return a collection with data about archives belonging to this realm
    */
  def archivesForRealm(realm: String): Iterable[HttpArchiveData] =
    archives.values.filter(_.realm.name == realm)
