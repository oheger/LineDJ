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

import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import scalaz._
import Scalaz._

/**
  * A service supporting validation of meta data for audio files.
  *
  * This service provides functions to validate single media files or whole
  * albums. The functions return ''Validation'' objects that - in case of
  * validation errors - contain lists with error codes.
  */
object MetaDataValidator {

  /**
    * A data class representing a single media file to be validated.
    *
    * @param mediumID the ID of the medium
    * @param uri      the URI identifying the file
    * @param metaData the meta data of the file
    */
  case class MediaFile(mediumID: MediumID, uri: String, metaData: MediaMetaData)

  /**
    * A data class representing an album to be validated. This is just a group
    * of files with meta data that is checked for certain criteria.
    *
    * @param mediumID the ID of the medium
    * @param uri      the URI identifying the album
    * @param metaData a sequence with the meta data of the files of the album
    */
  case class MediaAlbum(mediumID: MediumID, uri: String, metaData: Seq[MediaMetaData])

  /**
    * An enumeration class representing the supported validation error codes.
    *
    * Each problem that can be detected during validation is assigned a
    * validation error code. The codes can be used to generate corresponding
    * error messages that can be displayed to the user.
    */
  object ValidationErrorCode extends Enumeration {
    type ValidationErrorCode = Value

    /** The file's meta data does not define a title. */
    val NoTitle: Value = Value

    /** The file's meta data does not define an artist. */
    val NoArtist: Value = Value

    /** The file's meta data does not define an album. */
    val NoAlbum: Value = Value

    /** The file's meta data does not define the playback duration. */
    val NoDuration: Value = Value

    /** The file's meta data does not define its size. */
    val NoSize: Value = Value

    /** The file's meta data does not define a track number. */
    val NoTrackNo: Value = Value

    /** The file's meta data does not define an inception year. */
    val NoInceptionYear: Value = Value

    /** The album contains files with incomplete meta data. */
    val MissingFileMetaData: Value = Value

    /** The album information is inconsistent in the files of an album. */
    val InconsistentAlbum: Value = Value

    /** The track numbers of the album files are inconsistent. */
    val InconsistentTrackNumber: Value = Value

    /** The files of an album have different inception year values. */
    val InconsistentInceptionYear: Value = Value

    /** The minimum track number of an album is not 1. */
    val MinimumTrackNumberNotOne: Value = Value
  }

  /**
    * An enumeration class defining the severity of validation errors.
    */
  object Severity extends Enumeration {
    type Severity = Value

    val Warning, Error = Value
  }

  /** Constant for an undefined property. */
  private val Undefined = "Undefined"

  /**
    * The track number to be assumed for a file of an album with an undefined
    * track number.
    */
  private val UndefinedTrackNo = 1

  /**
    * The inception year to be assumed for a file of an album that does not
    * define the inception year.
    */
  private val UndefinedInceptionYear = 0

  /**
    * An internally used data class for storing information about an album that
    * is needed during validation.
    *
    * @param albumNames  a set with album names
    * @param missingData flag whether there are files with missing meta data
    * @param minTrackNo  the minimum track number of an album file
    * @param maxTrackNo  the maximum track number of an album file
    */
  private case class AlbumValidationData(albumNames: Set[String],
                                         inceptionYears: Set[Int],
                                         missingData: Boolean,
                                         minTrackNo: Int,
                                         maxTrackNo: Int) {
    /**
      * Updates the information stored in this instance with the given meta
      * data object. After this method has been called for all files belonging
      * to the album, the complete validation information is available.
      *
      * @param data the meta data of a file
      * @return the updated ''AlbumValidationData''
      */
    def update(data: MediaMetaData): AlbumValidationData = {
      val track = data.trackNumber getOrElse UndefinedTrackNo
      val minTrack = math.min(minTrackNo, track)
      val maxTrack = math.max(maxTrackNo, track)
      AlbumValidationData(albumNames = albumNames + data.album.getOrElse(Undefined),
        inceptionYears = inceptionYears + data.inceptionYear.getOrElse(UndefinedInceptionYear),
        missingData = missingData || isMetaDataMissing(data),
        minTrackNo = minTrack,
        maxTrackNo = maxTrack)
    }
  }

  /**
    * An empty object with album validation data. This is used as starting
    * point of a new validation operation.
    */
  private val EmptyAlbumValidationData = AlbumValidationData(albumNames = Set.empty, missingData = false,
    maxTrackNo = 1, minTrackNo = Integer.MAX_VALUE, inceptionYears = Set.empty)

  import Severity._
  import ValidationErrorCode._

  /** Stores the error codes that have the severity warning. */
  private val CodesWithWarningSeverity = Set(NoAlbum, NoTrackNo, NoInceptionYear, MinimumTrackNumberNotOne)

  /** Validation result for a meta data file. */
  type MetaDataFileValidation = ValidationNel[ValidationErrorCode, MediaFile]

  /** Validation result for a meta data album. */
  type MetaDataAlbumValidation = ValidationNel[ValidationErrorCode, MediaAlbum]


  /**
    * Returns the severity of the given validation code.
    *
    * @param code the validation code
    * @return the severity for this code
    */
  def severity(code: ValidationErrorCode): Severity =
    if (CodesWithWarningSeverity.contains(code)) Severity.Warning
    else Severity.Error

  /**
    * Validates the specified media file.
    *
    * @param file the file to be validated
    * @return the validation result for the file
    */
  def validateFile(file: MediaFile): MetaDataFileValidation = {
    (validateFileProperty(file, file.metaData.title, NoTitle) |@|
      validateFileProperty(file, file.metaData.artist, NoArtist) |@|
      validateFileProperty(file, file.metaData.album, NoAlbum) |@|
      validateFileProperty(file, file.metaData.duration, NoDuration) |@|
      validateFileProperty(file, file.metaData.trackNumber, NoTrackNo) |@|
      validateFileProperty(file, file.metaData.inceptionYear, NoInceptionYear) |@|
      validateFileSize(file)) { (f, _, _, _, _, _, _) => f }
  }

  /**
    * Validates the specified album. Albums with no files or whose number of
    * files exceeds the specified threshold are ignored. For all others, a
    * validation of the files contained is performed.
    *
    * @param album the album
    * @return the validation result for this album
    */
  def validateAlbum(album: MediaAlbum, maxFiles: Int = Integer.MAX_VALUE): MetaDataAlbumValidation =
    if (album.metaData.isEmpty || album.metaData.size > maxFiles) album.successNel[ValidationErrorCode]
    else {
      val validData = album.metaData.foldLeft(EmptyAlbumValidationData)(_.update(_))
      (validateAlbumName(album, validData) |@|
        validateMissingMetaData(album, validData) |@|
        validateConsistentTrackNumbers(album, validData) |@|
        validateMinTrackNumber(album, validData) |@|
        validateConsistentInceptionYears(album, validData)) { (f, _, _, _, _) => f }
    }

  /**
    * Generic validation function for meta data properties of media files. The
    * function checks whether the property specified is defined. If not, a
    * failed validation result with the given code is returned.
    *
    * @param file    the file
    * @param optProp the option with the property
    * @param code    the validation error code corresponding to the property
    * @return the validation result
    */
  private def validateFileProperty(file: MediaFile, optProp: Option[_], code: ValidationErrorCode):
  MetaDataFileValidation = optProp match {
    case Some(_) => file.successNel[ValidationErrorCode]
    case None => code.failureNel[MediaFile]
  }

  /**
    * Validation function for the size of a media file.
    *
    * @param file the file
    * @return the validation result
    */
  private def validateFileSize(file: MediaFile): MetaDataFileValidation =
    if (file.metaData.size > 0) file.successNel[ValidationErrorCode]
    else NoSize.failureNel[MediaFile]

  /**
    * Validation function for testing whether all files of an album have the
    * same album name.
    *
    * @param album the album
    * @param data  the album validation data
    * @return the validation result
    */
  private def validateAlbumName(album: MediaAlbum, data: AlbumValidationData): MetaDataAlbumValidation =
    albumResult(album, InconsistentAlbum, data.albumNames.size == 1)

  /**
    * Validation function for missing meta data for a file belonging to an
    * album.
    *
    * @param album the album
    * @param data  the album validation data
    * @return the validation result
    */
  private def validateMissingMetaData(album: MediaAlbum, data: AlbumValidationData): MetaDataAlbumValidation =
    albumResult(album, MissingFileMetaData, !data.missingData)

  /**
    * Validation function for the track numbers of the files on an album. It is
    * checked whether there are no gaps in the track numbers. However, track
    * numbers do not necessarily have to start with 1; the folder could only be
    * a part of the complete album.
    *
    * @param album the album
    * @param data  the album validation data
    * @return the validation result
    */
  private def validateConsistentTrackNumbers(album: MediaAlbum, data: AlbumValidationData): MetaDataAlbumValidation =
    albumResult(album, InconsistentTrackNumber, data.maxTrackNo - data.minTrackNo + 1 == album.metaData.size)

  /**
    * Validation function for the minimum track number of an album.
    *
    * @param album the album
    * @param data  the album validation data
    * @return the validation result
    */
  private def validateMinTrackNumber(album: MediaAlbum, data: AlbumValidationData): MetaDataAlbumValidation =
    albumResult(album, MinimumTrackNumberNotOne, data.minTrackNo == 1)

  /**
    * Validation function for the consistency of an album's inception year. It
    * is checked whether all files belonging to the album have the same
    * inception year.
    *
    * @param album the album
    * @param data  the album validation data
    * @return the validation result
    */
  private def validateConsistentInceptionYears(album: MediaAlbum, data: AlbumValidationData): MetaDataAlbumValidation =
    albumResult(album, InconsistentInceptionYear, data.inceptionYears.size == 1)

  /**
    * Creates a validation result for an album. Depending on the success flag,
    * either a success result or a failure with the given code is generated.
    *
    * @param album      the album
    * @param code       the error code
    * @param successful flag whether validation was successful
    * @return the validation result
    */
  private def albumResult(album: MediaAlbum, code: ValidationErrorCode, successful: Boolean): MetaDataAlbumValidation =
    if (successful) album.successNel[ValidationErrorCode]
    else code.failureNel[MediaAlbum]

  /**
    * Checks whether relevant meta data is missing in the given object. This is
    * used to detect missing data in the files of an album.
    *
    * @param data the meta data
    * @return '''true''' if data is missing; '''false''' otherwise
    */
  private def isMetaDataMissing(data: MediaMetaData): Boolean =
    data.album.empty || data.title.empty || data.artist.empty || data.duration.empty ||
      data.inceptionYear.empty || data.trackNumber.empty || data.size <= 0
}
