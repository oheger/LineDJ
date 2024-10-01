package de.oliver_heger.linedj.archiveadmin.validate

import de.oliver_heger.linedj.archiveadmin.validate.MetadataValidator.Severity.Severity
import de.oliver_heger.linedj.archiveadmin.validate.MetadataValidator.{MediaFile, ValidationResult}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.apache.pekko.stream.scaladsl.Flow

import scala.beans.BeanProperty

/**
  * An object defining several data classes and types that are used during meta
  * data validation operations.
  */
object ValidationModel:
  /**
    * Type definition for a function that is used to generate a display string
    * for a validated element. The function is passed the element's URI and has
    * to return the string to be displayed to the user.
    */
  type DisplayFunc = String => String

  /**
    * A data class representing a validated item. Instances have properties to
    * identify the item (which can be a single file, an album, or a complete
    * medium) plus the result of the validation.
    *
    * @param medium      the medium ID
    * @param uri         the URI of the item
    * @param displayFunc function to generate a display name
    * @param result      the result of the validation
    * @tparam R the result type of the validation result
    */
  case class ValidatedItem[R](medium: MediumID, uri: String, displayFunc: DisplayFunc, result: ValidationResult[R])

  /**
    * A data class used by the table model of the table with validation errors.
    *
    * An instance corresponds to one validation error. It identifies the item
    * that has been validated (in a human readable way) together with
    * information about the validation error.
    *
    * @param mediumName   the name of the medium
    * @param name         the name for the element
    * @param error        the validation error
    * @param severityIcon an icon for the severity
    * @param severity     the severity of this item
    */
  case class ValidationErrorItem(@BeanProperty mediumName: String,
                                 @BeanProperty name: String,
                                 @BeanProperty error: String,
                                 @BeanProperty severityIcon: AnyRef,
                                 severity: Severity)

  /**
    * The type of the flow that performs validation on items.
    *
    * The flow is passed a sequence with the files of a medium as input. It can
    * either validate them file by file or aggregate them to larger items (e.g.
    * albums). The output is the validation results for the files on the
    * current medium.
    */
  type ValidationFlow[R] = Flow[List[MediaFile], ValidatedItem[R], Any]
