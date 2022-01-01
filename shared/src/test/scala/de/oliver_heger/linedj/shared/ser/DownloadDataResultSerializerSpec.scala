/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.shared.ser

import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.shared.archive.media.DownloadDataResult
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''DownloadDataResultSerializer''.
  */
class DownloadDataResultSerializerSpec extends AnyFlatSpec with Matchers {
  "DownloadDataResultSerializer" should "report a valid identifier" in {
    val serializer = new DownloadDataResultSerializer

    serializer.identifier should be > 40 // 0 - 40 is reserved
  }

  it should "not need a manifest" in {
    val serializer = new DownloadDataResultSerializer

    serializer.includeManifest shouldBe false
  }

  it should "convert a message to binary" in {
    val data = FileTestHelper.testBytes()
    val msg = DownloadDataResult(ByteString(data))
    val serializer = new DownloadDataResultSerializer

    serializer.toBinary(msg) should be(FileTestHelper.testBytes())
  }

  it should "deserialize a message" in {
    val serializer = new DownloadDataResultSerializer

    val msg = serializer.fromBinary(FileTestHelper.testBytes()).asInstanceOf[DownloadDataResult]
    msg.data.utf8String should be(FileTestHelper.TestData)
  }
}
