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

package de.oliver_heger.linedj.archiveadmin.validate

import akka.stream.scaladsl.Flow
import de.oliver_heger.linedj.archiveadmin.validate.MetaDataValidator.MediaFile
import de.oliver_heger.linedj.archiveadmin.validate.ValidationModel.{DisplayFunc, ValidatedItem, ValidationFlow}
import de.oliver_heger.linedj.platform.app.ClientApplication
import net.sf.jguiraffe.gui.app.{ApplicationBuilderData, OpenWindowCommand}
import net.sf.jguiraffe.locators.Locator

import scala.concurrent.{ExecutionContext, Future}

object OpenValidationWindowCommand {
  /**
    * The name of the property under which the validation flow is stored in the
    * builder context.
    */
  val PropFlow = "validationFlow"

  /** The prefix for all properties related to meta data validation. */
  val PrefixValidationConfig = "media.validation."

  /**
    * The configuration property that defines the parallelism for the
    * validation of media files. The validation stream runs that many
    * validations in parallel.
    */
  val PropFileValidationParallelism: String = PrefixValidationConfig + "fileValidationParallelism"

  /** The default value for the file validation parallelism property. */
  val DefaultFileValidationParallelism = 4
}

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
  */
abstract class OpenValidationWindowCommand(scriptLocator: Locator, app: ClientApplication) extends OpenWindowCommand(scriptLocator) {

  import OpenValidationWindowCommand._

  /**
    * @inheritdoc This implementation invokes ''createValidationFlow()'' and
    *             stores the flow under a well-known key in the builder
    *             context.
    */
  override def prepareBuilderData(builderData: ApplicationBuilderData): Unit = {
    super.prepareBuilderData(builderData)
    builderData.addProperty(PropFlow, createValidationFlow())
  }

  /**
    * Creates the flow that does the actual validation.
    *
    * @return the validation flow
    */
  protected def createValidationFlow(): ValidationFlow
}

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
class OpenFileValidationWindowCommand(scriptLocator: Locator, app: ClientApplication) extends OpenValidationWindowCommand(scriptLocator, app) {

  import OpenValidationWindowCommand._

  /* The execution context.*/
  private implicit val ec: ExecutionContext = app.clientApplicationContext.actorSystem.dispatcher

  /** The parallelism for validation during stream processing. */
  lazy val parallelism: Int = app.clientApplicationContext.managementConfiguration
    .getInt(PropFileValidationParallelism, DefaultFileValidationParallelism)

  override protected def createValidationFlow(): ValidationFlow =
    Flow[MediaFile].mapAsyncUnordered(parallelism) { file =>
      Future {
        val valResult = MetaDataValidator.validateFile(file)
        ValidatedItem(file.mediumID, file.uri, createDisplayFunc(file), valResult)
      }
    }

  /**
    * Generates the display function for the given media file. If the title is
    * available in the meta data, it is returned. Otherwise, the name to be
    * displayed is derived from the URI.
    *
    * @param file the file in question
    * @return the display function for this file
    */
  private def createDisplayFunc(file: MediaFile): DisplayFunc = file.metaData.title match {
    case Some(t) => _ => t
    case None => uri => fileNameFromUri(uri)
  }

  /**
    * Generates a file name from the file URI. Tries to extract the last
    * component of the URI.
    *
    * @param uri the URI
    * @return the file name
    */
  private def fileNameFromUri(uri: String): String = {
    val lastSlash = uri.replace('\\', '/').lastIndexOf('/')
    if (lastSlash >= 0) uri.substring(lastSlash + 1)
    else uri
  }
}
