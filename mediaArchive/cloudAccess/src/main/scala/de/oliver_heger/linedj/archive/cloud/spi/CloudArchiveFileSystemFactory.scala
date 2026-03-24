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

package de.oliver_heger.linedj.archive.cloud.spi

import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.delegate.ExtensibleFileSystem
import de.oliver_heger.linedj.archive.cloud.spi.CloudArchiveFileSystemFactory.CloudArchiveFileSystem
import org.apache.pekko.util.Timeout

import java.util.{Locale, ServiceLoader}
import scala.util.{Failure, Try}

/**
  * An object providing declarations related to file system factories and
  * functionality to manage concrete [[CloudArchiveFileSystemFactory]]
  * implementations via Java's service loader mechanism.
  *
  * This object manages a service loader for [[CloudArchiveFileSystemFactory]]
  * objects and allows access to specific instances by their name. That way,
  * such factories can be used dynamically in a straight-forward way also in
  * non-OSGi applications.
  */
object CloudArchiveFileSystemFactory:
  /**
    * A type alias to define a file system from ''CloudFiles'' in the flavor as
    * it is used for accessing media files from cloud archives.
    */
  type CloudArchiveFileSystem[ID, FILE <: Model.File[ID], FOLDER <: Model.Folder[ID]] =
    ExtensibleFileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]]

  /**
    * Stores a map with all [[CloudArchiveFileSystemFactory]] instances that
    * have been found on the classpath.
    */
  private val factories = loadFactories()

  /**
    * Returns a flag whether a [[CloudArchiveFileSystemFactory]] with the given
    * name (case-insensitive) exists on the classpath.
    *
    * @param name the name of the factory (ignoring case)
    * @return a flag whether this factory is available
    */
  def existsFactory(name: String): Boolean =
    factories.contains(factoryName(name))

  /**
    * Returns the [[CloudArchiveFileSystemFactory]] instance with the given
    * name or throws an exception if it does not exist.
    *
    * @param name the name of the factory (ignoring case)
    * @return the [[CloudArchiveFileSystemFactory]] instance with this name
    */
  def getFactory(name: String): CloudArchiveFileSystemFactory =
    Try:
      factories(factoryName(name))
    .recoverWith:
      case _: NoSuchElementException =>
        Failure(new NoSuchElementException(s"Unknown CloudArchiveFileSystemFactory: $name."))
    .get

  /**
    * Obtains all existing factory implementations on the classpath via the
    * corresponding service loader. The factories are returned as a map using
    * the names as keys in lowercase.
    *
    * @return a [[Map]] with the found factory objects
    */
  private def loadFactories(): Map[String, CloudArchiveFileSystemFactory] =
    import scala.jdk.CollectionConverters.*
    val loader = ServiceLoader.load(classOf[CloudArchiveFileSystemFactory])

    loader.asScala.map: factory =>
      factoryName(factory.name) -> factory
    .toMap

  /**
    * Processes the given string to be used as a name of a factory, so that it
    * can be used for comparisons ignoring case.
    *
    * @param s the string
    * @return the processed string
    */
  private def factoryName(s: String): String = s.toLowerCase(Locale.ROOT)
end CloudArchiveFileSystemFactory

/**
  * A trait defining an SPI to plug in different cloud-based protocols to be
  * used for media archives.
  *
  * For cloud archives, the actual access to media files is done via the API of
  * the ''CloudFiles'' project. This trait defines a factory interface for
  * creating a ''FileSystem'', which is the component from ''CloudFiles''
  * granting access to files stored in the cloud. This makes it possible to
  * load media files from all server types supported by the ''CloudFiles''
  * project.
  *
  * Concrete implementations of this trait need to depend on the ''CloudFiles''
  * module supporting the desired protocol. Then they have to deliver some
  * metadata and construct a properly initialized ''FileSystem'' object. To
  * achieve the latter, they have to parse the URI that is provided to the
  * factory method to create the ''FileSystem''.
  */
trait CloudArchiveFileSystemFactory:
  /** The type of IDs in the file system. */
  type ID

  /** The type of files in the file system. */
  type File <: Model.File[ID]

  /** The type of folders in the file system. */
  type Folder <: Model.Folder[ID]

  /**
    * Returns a name for the represented protocol. The name is used to find a
    * corresponding protocol implementation for a specific media archive.
    *
    * @return the protocol name
    */
  def name: String

  /**
    * Returns a flag whether this protocol implementation needs to access
    * multiple hosts. For instance, files could be downloaded from a different
    * server than the one that serves API calls. If this method returns
    * '''true''', an HTTP actor supporting multiple hosts is created for the
    * associated archive.
    *
    * @return a flag whether support for multi hosts is needed
    */
  def requiresMultiHostSupport: Boolean

  /**
    * Creates a [[CloudArchiveFileSystem]] object specific for the represented
    * protocol that allows access to the files stored in a location defined by
    * the passed in URI. It is up to a concrete implementation how this URI is
    * interpreted and transformed into a configuration suitable for the
    * underlying protocol. The function returns a ''Try'' as the URI may be
    * invalid. If it can be parsed successfully, the function returns a data
    * object with a fully initialized ''ExtensibleFileSystem'' and some
    * additional metadata needed for the correct interpretation of URIs to
    * media files.
    *
    * @param sourceUri the source URI of the archive from the configuration
    * @param timeout   a timeout for requests
    * @return a ''Try'' with an [[CloudArchiveFileSystem]] object
    */
  def createFileSystem(sourceUri: String, timeout: Timeout): Try[CloudArchiveFileSystem[ID, File, Folder]]
