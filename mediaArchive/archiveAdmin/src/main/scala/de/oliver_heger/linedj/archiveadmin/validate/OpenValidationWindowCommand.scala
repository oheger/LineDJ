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

package de.oliver_heger.linedj.archiveadmin.validate

import de.oliver_heger.linedj.archiveadmin.validate.MetaDataValidator.{MediaAlbum, MediaFile}
import de.oliver_heger.linedj.archiveadmin.validate.ValidationModel.{DisplayFunc, ValidatedItem, ValidationFlow}
import de.oliver_heger.linedj.platform.app.ClientApplication
import de.oliver_heger.linedj.shared.archive.media.UriHelper
import net.sf.jguiraffe.gui.app.{ApplicationBuilderData, OpenWindowCommand}
import net.sf.jguiraffe.locators.Locator
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.{ExecutionContext, Future}

object OpenValidationWindowCommand:
  /**
    * The name of the property under which the validation flow is stored in the
    * builder context.
    */
  val PropFlow = "validationFlow"

  /** The prefix for all properties related to meta data validation. */
  val PrefixValidationConfig = "media.validation."

  /**
    * The configuration property that defines the parallelism for the
    * validation of archive items. The validation stream runs that many
    * validations in parallel.
    */
  val PropValidationParallelism: String = PrefixValidationConfig + "validationParallelism"

  /** The default value for the file validation parallelism property. */
  val DefaultFileValidationParallelism = 4

  /**
    * Generates a name by prefixing the parent name to the given name. This is
    * used by the display function. An element name alone may be ambiguous, but
    * together with the parent name, there is fewer chance for a name
    * collision. In addition, URL-decoding is applied if necessary.
    *
    * @param uri  the element URI
    * @param name the name of the element
    * @return the resulting name with the parent prefix
    */
  def appendParent(uri: String, name: String): String =
    val parentName = UriHelper.extractName(UriHelper.extractParent(uri))
    UriHelper.urlDecode(UriHelper.concat(parentName, name))

/**
  * A base class for a command that opens the validation dialog for a specific
  * kind of validation.
  *
  * The type of validation to be performed is determined by a validation flow
  * instance. This base class implements the basic steps for opening the
  * dialog, but delegates the creation of the validation flow to concrete sub
  * classes.
  *
  * @param scriptLocator the locator to the builder script to be executed
  * @param app           the current application
  * @tparam V the type of the validation flow used by this command
  */
abstract class OpenValidationWindowCommand[V](scriptLocator: Locator, app: ClientApplication)
  extends OpenWindowCommand(scriptLocator):

  import OpenValidationWindowCommand._

  /** The parallelism for validation during stream processing. */
  lazy val parallelism: Int = app.clientApplicationContext.managementConfiguration
    .getInt(PropValidationParallelism, DefaultFileValidationParallelism)

  /* The execution context.*/
  protected implicit def ec: ExecutionContext = app.clientApplicationContext.actorSystem.dispatcher

  /**
    * @inheritdoc This implementation invokes ''createValidationFlow()'' and
    *             stores the flow under a well-known key in the builder
    *             context.
    */
  override def prepareBuilderData(builderData: ApplicationBuilderData): Unit =
    super.prepareBuilderData(builderData)
    builderData.addProperty(PropFlow, createValidationFlow())

  /**
    * Creates the flow that does the actual validation.
    *
    * @return the validation flow
    */
  protected def createValidationFlow(): ValidationFlow[V]

/**
  * A special command implementation to open the validation dialog for a simple
  * file-based validation.
  *
  * The validation flow provided by this class checks the properties of each
  * encountered media file.
  *
  * @param scriptLocator the locator to the builder script to be executed
  * @param app           the current application
  */
class OpenFileValidationWindowCommand(scriptLocator: Locator, app: ClientApplication)
  extends OpenValidationWindowCommand[MediaFile](scriptLocator, app):

  override protected def createValidationFlow(): ValidationFlow[MediaFile] =
    Flow[List[MediaFile]].mapConcat(identity)
      .mapAsyncUnordered(parallelism) { file =>
        Future:
          val valResult = MetaDataValidator.validateFile(file)
          ValidatedItem(file.mediumID, file.uri, createDisplayFunc(file), valResult)
      }

  /**
    * Generates the display function for the given media file. If the title is
    * available in the meta data, it is returned. Otherwise, the name to be
    * displayed is derived from the URI.
    *
    * @param file the file in question
    * @return the display function for this file
    */
  private def createDisplayFunc(file: MediaFile): DisplayFunc = file.metaData.title match
    case Some(t) => uri => OpenValidationWindowCommand.appendParent(uri, t)
    case None => uri => displayNameFromUri(uri)

  /**
    * Generates a display name from the file URI. This name consists of the
    * parent component (typically the album name) and the file name.
    *
    * @param uri the URI
    * @return the display name
    */
  private def displayNameFromUri(uri: String): String =
    OpenValidationWindowCommand.appendParent(uri, UriHelper extractName uri)

/**
  * A command implementation to open the validation dialog for an album-based
  * validation.
  *
  * The validation flow created by this class groups files by their album URIs.
  * Then each group is transformed to a ''MediaAlbum'' and passed to the
  * ''MetaDataValidator''.
  *
  * @param scriptLocator the locator to the builder script to be executed
  * @param app           the current application
  */
class OpenAlbumValidationWindowCommand(scriptLocator: Locator, app: ClientApplication)
  extends OpenValidationWindowCommand[MediaAlbum](scriptLocator, app):
  override protected def createValidationFlow(): ValidationFlow[MediaAlbum] =
    Flow[List[MediaFile]].mapConcat(groupToAlbums)
      .mapAsyncUnordered(parallelism) { album =>
        Future:
          val result = MetaDataValidator.validateAlbum(album)
          ValidatedItem(album.mediumID, album.uri, displayFunc, result)
      }

  /**
    * Generates ''MediaAlbum'' objects for the given list of media files. The
    * files are grouped by their parent URI. It is expected that all files
    * belong to the same medium.
    *
    * @param files a list with all media files of a medium
    * @return a list with corresponding albums
    */
  private def groupToAlbums(files: List[MediaFile]): List[MediaAlbum] =
    lazy val mid = files.head.mediumID // only executed if there are files
    val albumFiles = files.groupBy(f => parentUri(f.uri))
    albumFiles.map(e => MediaAlbum(mid, e._1, e._2 map (_.metaData))).toList

  /**
    * Determines the parent URI of the given URI. The last component is split.
    *
    * @param uri the URI
    * @return the parent URI
    */
  private def parentUri(uri: String): String = UriHelper extractParent uri

  /**
    * Implementation of the display function for albums. This function returns
    * the parent component (typically the artist) plus the album name.
    *
    * @param uri the full URI of the album
    * @return the string to be displayed for the album
    */
  private def displayFunc(uri: String): String =
    OpenValidationWindowCommand.appendParent(uri, UriHelper extractName uri)
