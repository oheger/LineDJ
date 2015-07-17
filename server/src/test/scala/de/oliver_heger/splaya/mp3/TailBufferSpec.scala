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
package de.oliver_heger.splaya.mp3

import de.oliver_heger.splaya.FileTestHelper
import de.oliver_heger.splaya.io.ChannelHandler.ArraySource
import de.oliver_heger.splaya.io.DynamicInputStream
import org.scalatest.{FlatSpec, Matchers}

object TailBufferSpec {
  /** The test buffer size. */
  private val BufferSize = 32

  /** The expected result of the tail operation for the test buffer size. */
  private lazy val TestTail = createTail()

  /**
   * Creates an array source object with the test bytes.
   * @return an array source for the test bytes
   */
  private def testBytesSource(): ArraySource =
    DynamicInputStream arraySourceFor FileTestHelper.testBytes()

  /**
   * Creates the expected tail result.
   * @return the expected tail result
   */
  private def createTail(): Array[Byte] = {
    val data = FileTestHelper.testBytes()
    data.drop(data.length - BufferSize)
  }
}

/**
 * Test class for ''TailBuffer''.
 */
class TailBufferSpec extends FlatSpec with Matchers {

  import TailBufferSpec._
  import de.oliver_heger.splaya.FileTestHelper._

  "A TailBuffer" should "return an empty array if it contains no data" in {
    val buffer = new TailBuffer(100)

    buffer.tail() should have length 0
  }

  it should "return a smaller array for the last block if there is not enough data" in {
    val buffer = new TailBuffer(16384)
    val data = testBytes()

    buffer.addData(DynamicInputStream arraySourceFor data) should be(buffer)
    buffer.tail() should be(data)
  }

  it should "drop data if a block is larger than the configured size" in {
    val buffer = new TailBuffer(BufferSize)

    buffer addData testBytesSource()
    buffer.tail() should be(TestTail)
  }

  it should "process multiple larger blocks correctly" in {
    val blockSize = 2 * BufferSize
    val mod = testBytes().length % blockSize
    val buffer = new TailBuffer(BufferSize)

    for (block <- testBytes().drop(mod).grouped(blockSize)) {
      buffer.addData(DynamicInputStream arraySourceFor block)
    }
    buffer.tail() should be(TestTail)
  }

  it should "process small blocks correctly as well" in {
    val blockSize = BufferSize / 3
    val buffer = new TailBuffer(BufferSize)

    for (block <- testBytes() grouped blockSize) {
      buffer.addData(DynamicInputStream arraySourceFor block)
    }
    buffer.tail() should be(TestTail)
  }

  it should "process small blocks after big blocks correctly" in {
    val buffer = new TailBuffer(BufferSize)

    buffer.addData(DynamicInputStream.arraySourceFor(testBytes().reverse))
    for (block <- testBytes() grouped 16) {
      buffer.addData(DynamicInputStream arraySourceFor block)
    }
    buffer.tail() should be(TestTail)
  }

  it should "remember parts of a big block when a small one arrives" in {
    val bytes = testBytes()
    val bigIndex = bytes.length - BufferSize + 2
    val buffer = new TailBuffer(BufferSize)

    for (block <- testBytes() grouped bigIndex) {
      buffer.addData(DynamicInputStream arraySourceFor block)
    }
    buffer.tail() should be(TestTail)
  }
}
