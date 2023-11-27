/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.extract.id3.model

import de.oliver_heger.linedj.test.FileTestHelper
import org.apache.pekko.util.ByteString
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''ID3Tag''.
  */
class ID3TagSpec extends AnyFlatSpec with Matchers:
  "An ID3Tag" should "return an empty string if the tag has not content" in:
    val tag = ID3Tag("TAG", ByteString())
    tag.asString should be("")

  it should "return an empty string if the tag content is a single 0 byte" in:
    val tag = ID3Tag("TAG", ByteString(0.toByte))
    tag.asString should be("")

  it should "return an empty string if the tag content consists only of 0 bytes" in:
    val content = new Array[Byte](3)
    val tag = ID3Tag("TAG", ByteString(content))
    tag.asString should be("")

  it should "handle the absence of an encoding byte" in:
    val text = "This is a test!"
    val tag = ID3Tag("Tag", ByteString(text))
    tag.asString should be(text)

  it should "allow querying the tag content as byte array" in:
    val content = FileTestHelper.testBytes()
    val tag = ID3Tag("Tag", ByteString(content))

    val data = tag.asBytes
    val data2 = tag.asBytes
    data should not be theSameInstanceAs(content)
    data should not be theSameInstanceAs(data2)
    data should be(content)
    data2 should be(data)
