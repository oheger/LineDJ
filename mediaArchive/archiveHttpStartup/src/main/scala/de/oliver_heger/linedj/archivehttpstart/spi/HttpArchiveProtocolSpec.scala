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

package de.oliver_heger.linedj.archivehttpstart.spi

import akka.util.Timeout
import com.github.cloudfiles.core.Model
import de.oliver_heger.linedj.archivehttp.io.HttpArchiveFileSystem

import scala.language.existentials
import scala.util.Try

object HttpArchiveProtocolSpec {
  /**
    * Definition of a type to represent an [[HttpArchiveProtocolSpec]] with
    * consistent yet unknown type parameters.
    */
  type GenericHttpArchiveProtocolSpec = HttpArchiveProtocolSpec[ID, FILE, FOLDER] forSome {
    type ID
    type FILE <: Model.File[ID]
    type FOLDER <: Model.Folder[ID]
  }
}

/**
  * A trait defining an SPI to plug in different HTTP-based protocols to be
  * used for media archives.
  *
  * The actual access to media files is done via the API of the ''CloudFiles''
  * project. This trait provides the information for creating a ''FileSystem''
  * required for loading media files. This makes it possible to load media
  * files from all server types supported by the ''CloudFiles'' project.
  *
  * Concrete implementations of this trait need to depend on the ''CloudFiles''
  * module supporting the desired protocol. Then they have to deliver some
  * metadata and construct a properly initialized ''FileSystem'' object. To
  * achieve the latter, they have to parse the URI defined in the configuration
  * of the associated HTTP archive.
  *
  * @tparam ID     the type of IDs in the file system
  * @tparam FILE   the type of files in the file system
  * @tparam FOLDER the type of folders in the file system
  */
trait HttpArchiveProtocolSpec[ID, FILE <: Model.File[ID], FOLDER <: Model.Folder[ID]] {
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
    * Creates a ''HttpArchiveFileSystem'' object specific for the represented
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
    * @return a ''Try'' with an ''HttpArchiveFileSystem'' object
    */
  def createFileSystemFromConfig(sourceUri: String, timeout: Timeout): Try[HttpArchiveFileSystem[ID, FILE, FOLDER]]
}
