/*
 * Copyright 2015 The Developers Team.
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
package de.oliver_heger.splaya.metadata

import de.oliver_heger.splaya.mp3.{ID3Header, ID3HeaderExtractor}
import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''MetaDataExtractionContext''.
 */
class MetaDataExtractionContextSpec extends FlatSpec with Matchers {
  "A MetaDataExtractionContext" should "provide an ID3 header extractor" in {
    val context = new MetaDataExtractionContext(null)

    context.headerExtractor shouldBe a[ID3HeaderExtractor]
  }

  it should "allow creating an ID3 frame extractor" in {
    val header = ID3Header(2, 100)
    val context = new MetaDataExtractionContext(null)

    val extractor = context createID3FrameExtractor header
    extractor.header should be(header)
    extractor.tagSizeLimit should be(Integer.MAX_VALUE)
  }

  it should "set a size limit for tags in the ID3 frame extractor" in {
    val header = ID3Header(2, 100)
    val MaxTagSize = 1024
    val context = new MetaDataExtractionContext(null, MaxTagSize)

    val extractor = context createID3FrameExtractor header
    extractor.tagSizeLimit should be(MaxTagSize)
  }

  it should "allow creating an MP3 data extractor" in {
    val context = new MetaDataExtractionContext(null)

    val extractor = context.createMp3DataExtractor()
    extractor.getFrameCount should be(0)
  }

  it should "allow creating an ID3v1 extractor" in {
    val context = new MetaDataExtractionContext(null)

    val extractor = context.createID3v1Extractor()
    extractor.createTagProvider() shouldBe 'empty
  }
}
