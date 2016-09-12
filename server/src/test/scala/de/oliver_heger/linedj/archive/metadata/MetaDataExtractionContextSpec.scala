/*
 * Copyright 2015-2016 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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
package de.oliver_heger.linedj.archive.metadata

import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.mp3.{ID3Header, ID3HeaderExtractor}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object MetaDataExtractionContextSpec {
  /** Constant for the maximum tag size. */
  private val MaxTagSize = 1024
}

/**
 * Test class for ''MetaDataExtractionContext''.
 */
class MetaDataExtractionContextSpec extends FlatSpec with Matchers with MockitoSugar {
  import MetaDataExtractionContextSpec._

  /**
   * Creates a test context object with a mock configuration.
   * @return the test context
   */
  private def createContext(): MetaDataExtractionContext = {
    val config = mock[MediaArchiveConfig]
    when(config.tagSizeLimit).thenReturn(MaxTagSize)
    new MetaDataExtractionContext(null, config)
  }

  "A MetaDataExtractionContext" should "provide an ID3 header extractor" in {
    val context = createContext()

    context.headerExtractor shouldBe a[ID3HeaderExtractor]
  }

  it should "allow creating an ID3 frame extractor" in {
    val header = ID3Header(2, 100)
    val context = createContext()

    val extractor = context createID3FrameExtractor header
    extractor.header should be(header)
    extractor.tagSizeLimit should be(MaxTagSize)
  }

  it should "allow creating an MP3 data extractor" in {
    val context = createContext()

    val extractor = context.createMp3DataExtractor()
    extractor.getFrameCount should be(0)
  }

  it should "allow creating an ID3v1 extractor" in {
    val context = createContext()

    val extractor = context.createID3v1Extractor()
    extractor.createTagProvider() shouldBe 'empty
  }
}
