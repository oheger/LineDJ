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

package de.oliver_heger.linedj.archiveadmin.validate

import de.oliver_heger.linedj.archiveadmin.validate.MetadataValidator.Severity
import de.oliver_heger.linedj.archiveadmin.validate.MetadataValidator.ValidationErrorCode.ValidationErrorCode
import de.oliver_heger.linedj.archiveadmin.validate.ValidationModel.{ValidatedItem, ValidationErrorItem}
import de.oliver_heger.linedj.shared.archive.media.AvailableMedia
import net.sf.jguiraffe.gui.app.ApplicationContext
import scalaz.{Failure, Success}

import scala.beans.BeanProperty

/**
  * A class that generates table items with human-readable information from the
  * raw results of a validation process.
  *
  * @param applicationContext the application context (for resource resolution)
  * @param resourcePrefix     prefix for resource resolution
  */
class ValidationItemConverter(applicationContext: ApplicationContext, resourcePrefix: String):
  /** The icon representing the severity ''warning''. */
  @BeanProperty var iconSeverityWarning: AnyRef = _

  /** The icon representing the severity ''error''. */
  @BeanProperty var iconSeverityError: AnyRef = _

  /** A string for the name of an unknown medium. */
  @BeanProperty var unknownMediumName: String = _

  /**
    * Generates table items from a ''ValidatedItem'' object. It is checked
    * whether the item is a validation error. If this is the case, for each
    * error code a human-readable string is generated by looking up a resource
    * value, and a corresponding ''ValidationErrorItem'' object is produced.
    * Convention is that the resource ID for a severity code has a prefix
    * (defined by the ''resourcePrefix'' property) and ends on the name of the
    * code. For items indicating a successful validation an empty list is
    * returned.
    *
    * @param media   information about the media available
    * @param valItem the validated item
    * @return a list with corresponding validation error items
    */
  def generateTableItems(media: AvailableMedia, valItem: ValidatedItem[_]): List[ValidationErrorItem] =
    valItem.result match
      case Success(_) => List.empty
      case Failure(nel) =>
        val mediumName = fetchMediumName(media, valItem)
        errorItem(nel.head, mediumName, valItem) ::
          nel.tail.toList.map(c => errorItem(c, mediumName, valItem))


  /**
    * Generates an error item for the specified parameters.
    *
    * @param code       the error code
    * @param mediumName the medium name
    * @param valItem    the validated item
    * @return the error item
    */
  private def errorItem(code: ValidationErrorCode, mediumName: String, valItem: ValidatedItem[_]): ValidationErrorItem =
    ValidationErrorItem(mediumName = mediumName,
      error = applicationContext.getResourceText(resourcePrefix + code),
      name = valItem.displayFunc(valItem.uri),
      severityIcon = severityIcon(code),
      severity = MetadataValidator.severity(code))

  /**
    * Obtains the name of the medium for the given validated item.
    *
    * @param media   information about the media available
    * @param valItem the validated item
    * @return the name of the medium
    */
  private def fetchMediumName(media: AvailableMedia, valItem: ValidatedItem[_]): String =
    media.media.get(valItem.medium).map(_.name) getOrElse unknownMediumName

  /**
    * Returns the icon for the severity of the given error code.
    *
    * @param code the error code
    * @return the icon
    */
  private def severityIcon(code: ValidationErrorCode): AnyRef =
    if MetadataValidator.severity(code) == Severity.Error then iconSeverityError
    else iconSeverityWarning
