/*
 * Copyright 2015-2017 The Developers Team.
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
package de.oliver_heger.linedj.extract.metadata

import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''ID3TagProvider''.
 */
class MetaDataProviderSpec extends FlatSpec with Matchers {
  "An ID3TagProvider" should "parse a numeric inception year" in {
    val provider = new MetaDataProviderTestImpl(inceptionYearString = Some("1984"))
    provider.inceptionYear.get should be(1984)
  }

  it should "return None for a non-numeric inception year" in {
    val provider = new MetaDataProviderTestImpl(
      inceptionYearString = Some("Nineteeneightyfour"))
    provider.inceptionYear shouldBe 'empty
  }

  it should "handle a non existing inception year" in {
    val provider = new MetaDataProviderTestImpl
    provider.inceptionYear shouldBe 'empty
  }

  it should "convert a track number if it is fully numeric" in {
    val provider = new MetaDataProviderTestImpl(trackNoString = Some("42"))
    provider.trackNo.get should be(42)
  }

  it should "convert a partly numeric track number" in {
    val provider = new MetaDataProviderTestImpl(trackNoString = Some("42 / 100"))
    provider.trackNo.get should be(42)
  }

  it should "return None for a non-numeric track number" in {
    val provider = new MetaDataProviderTestImpl(trackNoString = Some("NaN"))
    provider.trackNo shouldBe 'empty
  }

  it should "handle an undefined track number" in {
    val provider = new MetaDataProviderTestImpl
    provider.trackNo shouldBe 'empty
  }

  it should "handle a track number that is empty" in {
    val provider = new MetaDataProviderTestImpl(trackNoString = Some(""))
    provider.trackNo shouldBe 'empty
  }

  /**
   * A concrete test implementation of ID3TagProvider.
   */
  private class MetaDataProviderTestImpl(val inceptionYearString: Option[String] = None,
                                         val trackNoString: Option[String] = None) extends
  MetaDataProvider {
    val title = None
    val artist = None
    val album = None
  }

}
