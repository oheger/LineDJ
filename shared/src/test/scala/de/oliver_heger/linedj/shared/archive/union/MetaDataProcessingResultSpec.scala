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

package de.oliver_heger.linedj.shared.archive.union

import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object MetaDataProcessingResultSpec {
  /** A test path. */
  private val TestPath = "test/mp3/song.mp3"

  /** A test URI. */
  private val TestUri = "song://test/song.mp3"

  /** A test medium ID. */
  private val TestMedium = MediumID("testMedium", Some("Settings"))

  /**
    * Creates a test result object without meta data.
    *
    * @return the test result
    */
  private def createResult(): MetaDataProcessingSuccess =
    MetaDataProcessingSuccess(TestPath, TestMedium, TestUri, null)
}

/**
  * Test class for ''MetaDataProcessingResult'' and
  * ''MetaDataProcessingError''.
  */
class MetaDataProcessingResultSpec extends AnyFlatSpec with Matchers {

  import MetaDataProcessingResultSpec._

  "A MetaDataProcessingResult" should "allow setting meta data" in {
    val metaData = MediaMetaData(title = Some("Fear of the Dark"),
      artist = Some("Iron Maidon"))

    val orgResult = createResult()
    val result = orgResult.withMetaData(metaData)
    result.uri should be(orgResult.uri)
    result.path should be(orgResult.path)
    result.mediumID should be(orgResult.mediumID)
    result.metaData should be(metaData)
  }

  it should "allow creating a derived error result" in {
    val exception = new Exception("test procesing error")
    val orgResult = createResult()

    val errResult = orgResult.toError(exception)
    errResult.uri should be(orgResult.uri)
    errResult.path should be(orgResult.path)
    errResult.mediumID should be(orgResult.mediumID)
    errResult.exception should be(exception)
  }
}
