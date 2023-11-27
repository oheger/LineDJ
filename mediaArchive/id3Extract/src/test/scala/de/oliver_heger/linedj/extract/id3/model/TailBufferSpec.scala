/*
 * Copyright 2015-2023 The Developers Team.
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
package de.oliver_heger.linedj.extract.id3.model

import de.oliver_heger.linedj.test.FileTestHelper
import org.apache.pekko.util.ByteString
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object TailBufferSpec:
  /** The test buffer size. */
  private val BufferSize = 32

  /** The expected result of the tail operation for the test buffer size. */
  private lazy val TestTail = createTail()

  /**
    * Creates an array source object with the test bytes.
    *
    * @return an array source for the test bytes
    */
  private def testBytesString(): ByteString =
    ByteString(FileTestHelper.testBytes())

  /**
    * Creates the expected tail result.
    *
    * @return the expected tail result
    */
  private def createTail(): ByteString =
    val data = FileTestHelper.testBytes()
    ByteString(data.drop(data.length - BufferSize))

/**
  * Test class for ''TailBuffer''.
  */
class TailBufferSpec extends AnyFlatSpec with Matchers:

  import FileTestHelper.*
  import TailBufferSpec.*

  "A TailBuffer" should "return an empty array if it contains no data" in:
    val buffer = TailBuffer(100)

    buffer.tail() should have length 0

  it should "return a smaller array for the last block if there is not enough data" in:
    val data = ByteString(testBytes())

    val buffer = TailBuffer(16384).addData(data)
    buffer.tail() should be(data)

  it should "drop data if a block is larger than the configured size" in:
    val buffer = TailBuffer(BufferSize) addData testBytesString()

    buffer.tail() should be(TestTail)

  it should "process multiple larger blocks correctly" in:
    val buffer = TailBuffer(BufferSize, ByteString(testBytes().reverse))

    buffer.addData(ByteString(testBytes())).tail() should be(TestTail)

  it should "process small blocks correctly as well" in:
    val blockSize = BufferSize / 3
    var buffer =TailBuffer(BufferSize)

    for block <- testBytes() grouped blockSize do
      buffer = buffer.addData(ByteString(block))
    buffer.tail() should be(TestTail)

  it should "process small blocks after big blocks correctly" in:
    var buffer = TailBuffer(BufferSize).addData(ByteString(testBytes().reverse))

    for block <- testBytes() grouped 16 do
      buffer = buffer.addData(ByteString(block))
    buffer.tail() should be(TestTail)

  it should "remember parts of a big block when a small one arrives" in:
    val bytes = testBytes()
    val bigIndex = bytes.length - BufferSize + 2
    var buffer = TailBuffer(BufferSize)

    for block <- testBytes() grouped bigIndex do
      buffer = buffer.addData(ByteString(block))
    buffer.tail() should be(TestTail)
