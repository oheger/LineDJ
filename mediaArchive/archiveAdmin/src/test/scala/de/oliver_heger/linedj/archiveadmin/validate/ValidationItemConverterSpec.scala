/*
 * Copyright 2015-2021 The Developers Team.
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

import de.oliver_heger.linedj.archiveadmin.validate.MetaDataValidator.ValidationErrorCode.ValidationErrorCode
import de.oliver_heger.linedj.archiveadmin.validate.MetaDataValidator.{MediaFile, ValidationErrorCode, ValidationResult}
import de.oliver_heger.linedj.archiveadmin.validate.ValidationModel.{ValidatedItem, ValidationErrorItem}
import de.oliver_heger.linedj.archiveadmin.validate.ValidationTestHelper._
import de.oliver_heger.linedj.shared.archive.media.MediumID
import net.sf.jguiraffe.gui.app.ApplicationContext
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import scalaz.Scalaz._
import scalaz._

object ValidationItemConverterSpec {
  /** An object with available media. */
  private val TestMedia = createAvailableMedia(4)

  /** The prefix for error code resources. */
  private val ResourcePrefix = "code_"

  /** Suffix for a resolved resource. */
  private val ResolvedSuffix = "_resolved"

  /** Suffix for a display string. */
  private val DisplaySuffix = "_display"

  /** Simulates the warning icon. */
  private val IconWarning = "iconWarning"

  /** Simulates the error icon. */
  private val IconError = "iconError"

  /** Uri of the test item. */
  private val Uri = fileUri(1)

  /** The name of an unknown medium. */
  private val UnknownMedium = "<unknown medium>"

  /**
    * A test function to generate the display string. The function simply adds
    * a suffix to the given string.
    *
    * @param s the string
    * @return the display string
    */
  private def displayFunc(s: String): String = s + DisplaySuffix

  /**
    * Generates a validated item with the given validation result.
    *
    * @param result the validation result
    * @param mid    the medium ID
    * @return the validated item
    */
  private def createItem[V](result: ValidationResult[V], mid: MediumID = Medium): ValidatedItem[V] =
    ValidatedItem(result = result, medium = mid, displayFunc = displayFunc, uri = Uri)
}

/**
  * Test class for ''ValidationItemConverter''.
  */
class ValidationItemConverterSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import ValidationItemConverterSpec._

  "A ValidationItemConverter" should "handle a valid item" in {
    val item = createItem(MediaFile(Medium, Uri, metaData(1)).successNel[ValidationErrorCode])
    val helper = new ConverterTestHelper

    helper.convert(item) should have size 0
  }

  it should "convert an item with failures" in {
    val codes = List(ValidationErrorCode.NoInceptionYear, ValidationErrorCode.NoArtist,
      ValidationErrorCode.MinimumTrackNumberNotOne)
    val icons = List(IconWarning, IconError, IconWarning)
    val failures = codes.tail.foldLeft(codes.head.wrapNel)((lst, c) => lst.append(c.wrapNel))
    val result: ValidationResult[MediaFile] = Failure(failures)
    val expItems = codes.zip(icons).map { c =>
      ValidationErrorItem(testMediumInfo(1).name, Uri + DisplaySuffix,
        ResourcePrefix + c._1.toString + ResolvedSuffix, c._2, MetaDataValidator.severity(c._1))
    }
    val helper = new ConverterTestHelper

    helper.convert(createItem(result)) should contain theSameElementsAs expItems
  }

  it should "handle an unexpected medium ID" in {
    val result: ValidationResult[MediaFile] = Failure(ValidationErrorCode.NoTitle.wrapNel)
    val helper = new ConverterTestHelper

    val errorItem = helper.convert(createItem(result, mid = testMedium(42)))
    errorItem.head.mediumName should be(UnknownMedium)
  }

  /**
    * A test helper class managing a test instance and its dependencies.
    */
  private class ConverterTestHelper {
    /** Mock for the application context. */
    private val appCtx = createApplicationContext()

    /** The test converter instance. */
    private val converter = createConverter()

    /**
      * Invokes the test converter with the given item and returns the result.
      *
      * @param item the item to be converter
      * @return the generated error items
      */
    def convert(item: ValidatedItem[_]): List[ValidationErrorItem] =
      converter.generateTableItems(TestMedia, item)

    /**
      * Creates the test converter instance.
      *
      * @return the test converter instance
      */
    private def createConverter(): ValidationItemConverter = {
      val converter = new ValidationItemConverter(appCtx, ResourcePrefix)
      converter setIconSeverityWarning IconWarning
      converter setIconSeverityError IconError
      converter setUnknownMediumName UnknownMedium
      converter
    }

    /**
      * Creates a mock for an application context that is prepared to resolve
      * resource texts.
      *
      * @return the mock application context
      */
    private def createApplicationContext(): ApplicationContext = {
      val ctx = mock[ApplicationContext]
      Mockito.when(ctx.getResourceText(anyString())).thenAnswer((invocation: InvocationOnMock) =>
        s"${invocation.getArguments.head}$ResolvedSuffix")
      ctx
    }
  }

}
