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

import MetaDataValidator.ValidationErrorCode._
import ValidationTestHelper._
import de.oliver_heger.linedj.archiveadmin.validate.MetaDataValidator.{MediaAlbum, MediaFile, ValidationErrorCode}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import org.scalatest.{FlatSpec, Matchers}
import scalaz.{Failure, NonEmptyList, Success}

object MetaDataValidatorSpec {
  /**
    * Convenience function to convert a NonEmptyList to a plain list.
    *
    * @param nel the NonEmptyList to be converted
    * @tparam A the element type of the list
    * @return the resulting plain list
    */
  private def toList[A](nel: NonEmptyList[A]): List[A] =
    nel.head :: nel.tail.toList
}

/**
  * Test class for ''MetaDataValidator''.
  */
class MetaDataValidatorSpec extends FlatSpec with Matchers {

  import MetaDataValidatorSpec._

  /**
    * Returns a set with validation error codes that are considered errors.
    *
    * @return the error codes with severity error
    */
  private def errorCodes: Set[ValidationErrorCode] =
    Set(NoTitle, NoArtist, NoDuration, NoSize, MissingFileMetaData, InconsistentAlbum, InconsistentTrackNumber,
      InconsistentInceptionYear)

  /**
    * Returns a set with validation error codes that are considered warnings.
    *
    * @return the error codes with severity warning
    */
  private def warningCodes: Set[ValidationErrorCode] = Set(NoAlbum, NoTrackNo, NoInceptionYear,
    MinimumTrackNumberNotOne)

  "MetaDataValidator" should "handle all validation error codes" in {
    errorCodes ++ warningCodes should contain allElementsOf ValidationErrorCode.values
  }

  it should "correctly report the severity error for codes" in {
    errorCodes forall (MetaDataValidator.severity(_) == MetaDataValidator.Severity.Error) shouldBe true
  }

  it should "correctly report the severity warning for codes" in {
    warningCodes forall (MetaDataValidator.severity(_) == MetaDataValidator.Severity.Warning) shouldBe true
  }

  it should "return a successful file validation result" in {
    val mediaFile = MediaFile(Medium, fileUri(1), metaData(1))

    MetaDataValidator.validateFile(mediaFile) match {
      case Success(file) => file should be(mediaFile)
      case r => fail("Unexpected result: " + r)
    }
  }

  /**
    * Helper function to check a failed validation of a media file. A file with
    * the given meta data is constructed and validated. The result should
    * contain all the error codes specified.
    *
    * @param data  the meta data for the file
    * @param codes the expected error codes
    */
  private def checkFailedFileValidation(data: MediaMetaData, codes: ValidationErrorCode*): Unit = {
    val file = MediaFile(Medium, fileUri(1), data)
    MetaDataValidator.validateFile(file) match {
      case Failure(e) => toList(e) should contain allElementsOf codes
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "detect a missing title in file meta data" in {
    checkFailedFileValidation(metaData(1).copy(title = None), NoTitle)
  }

  it should "detect a missing title and artist in file meta data" in {
    checkFailedFileValidation(metaData(1).copy(title = None, artist = None), NoTitle, NoArtist)
  }

  it should "detect a missing album in file meta data" in {
    checkFailedFileValidation(metaData(1).copy(album = None), NoAlbum)
  }

  it should "detect a missing duration in file meta data" in {
    checkFailedFileValidation(metaData(1).copy(duration = None), NoDuration)
  }

  it should "detect a missing size in file meta data" in {
    checkFailedFileValidation(metaData(1).copy(size = 0), NoSize)
  }

  it should "detect a missing track number in file meta data" in {
    checkFailedFileValidation(metaData(1).copy(trackNumber = None), NoTrackNo)
  }

  it should "detect a missing inception year in file meta data" in {
    checkFailedFileValidation(metaData(1).copy(inceptionYear = None), NoInceptionYear)
  }

  it should "return a successful album validation result" in {
    val a = album(1, 16)
    MetaDataValidator.validateAlbum(a) match {
      case Success(va) => va should be(a)
      case r => fail("Unexpected result: " + r)
    }
  }

  /**
    * Helper function to check a failed validation of an album. The given in
    * album is passed to the validator, and it is checked whether the expected
    * error codes are returned.
    *
    * @param album the album
    * @param codes the expected error codes
    */
  private def checkFailedAlbumValidation(album: MediaAlbum, codes: ValidationErrorCode*): Unit = {
    MetaDataValidator.validateAlbum(album) match {
      case Failure(e) => toList(e) should contain allElementsOf codes
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "detect an inconsistent album name" in {
    val meta = metaData(16, 2)
    val a = album(1, 15, meta)

    checkFailedAlbumValidation(a, InconsistentAlbum)
  }

  it should "detect an inconsistent and missing album name" in {
    val meta = metaData(8).copy(album = None)
    val a = album(1, 7, meta)

    checkFailedAlbumValidation(a, InconsistentAlbum, MissingFileMetaData)
  }

  /**
    * Helper function for checking whether missing meta data for a file is
    * detected during album validation.
    *
    * @param meta a data object with a missing property
    */
  private def checkMissingMetaDataInAlbumFile(meta: MediaMetaData): Unit = {
    val a = album(1, 3, meta)

    checkFailedAlbumValidation(a, MissingFileMetaData)
  }

  it should "detect a missing title for a file of an album" in {
    checkMissingMetaDataInAlbumFile(metaData(8).copy(title = None))
  }

  it should "detect a missing artist for a file of an album" in {
    checkMissingMetaDataInAlbumFile(metaData(4).copy(artist = None))
  }

  it should "detect a missing playback duration for a file of an album" in {
    checkMissingMetaDataInAlbumFile(metaData(4).copy(duration = None))
  }

  it should "detect a missing inception year for a file of an album" in {
    checkMissingMetaDataInAlbumFile(metaData(4).copy(inceptionYear = None))
  }

  it should "detect a missing track number for a file of an album" in {
    val meta = metaData(8).copy(trackNumber = None)
    val a = album(1, 7, meta)

    checkFailedAlbumValidation(a, MissingFileMetaData, InconsistentTrackNumber)
  }

  it should "detect a missing size information for a file of an album" in {
    checkMissingMetaDataInAlbumFile(metaData(4).copy(size = 0))
  }

  it should "detect a minimum track number that is not 1" in {
    val albumOrg = album(1, 8)
    val renumberedSongs = albumOrg.metaData map (d => d.copy(trackNumber = d.trackNumber.map(_ + 1)))
    val a = albumOrg.copy(metaData = renumberedSongs)

    checkFailedAlbumValidation(a, MinimumTrackNumberNotOne)
  }

  it should "detect an inconsistent inception year for an album" in {
    val meta = metaData(4).copy(inceptionYear = Some(1950))
    val a = album(1, 3, meta)

    checkFailedAlbumValidation(a, InconsistentInceptionYear)
  }

  it should "accept an empty album as valid" in {
    val a = MediaAlbum(Medium, "emptyAlbum", Nil)

    MetaDataValidator.validateAlbum(a) match {
      case Success(res) => res should be(a)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "ignore albums with too many files on them" in {
    val Count = 32
    val a = album(1, Count, metaData(100).copy(title = None))

    MetaDataValidator.validateAlbum(a, Count) match {
      case Success(res) => res should be(a)
      case r => fail("Unexpected result: " + r)
    }
  }
}
