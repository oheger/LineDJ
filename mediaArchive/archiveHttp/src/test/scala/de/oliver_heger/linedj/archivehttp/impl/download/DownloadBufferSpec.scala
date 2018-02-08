/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.impl.download

import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import org.scalatest.{FlatSpec, Matchers}

import scala.annotation.tailrec

/**
  * Test class for ''DownloadBuffer''.
  */
class DownloadBufferSpec extends FlatSpec with Matchers {
  "A DownloadBuffer" should "return a correct size of an empty buffer" in {
    DownloadBuffer.empty.size should be(0)
  }

  it should "return correct chunks for an empty buffer" in {
    DownloadBuffer.empty.chunks.isEmpty shouldBe true
  }

  it should "correctly handle a fetch operation if it is empty" in {
    val buffer = DownloadBuffer.empty

    val (data, buffer2) = buffer fetchData 1
    data should be(None)
    buffer2 should be theSameInstanceAs buffer
  }

  it should "allow adding and querying chunks" in {
    val chunks = ByteString(FileTestHelper.TestData).grouped(32).toList

    val buffer = chunks.foldLeft(DownloadBuffer.empty)(_ addChunk _)
    buffer.size should be(FileTestHelper.TestData.length)
    buffer.chunks.toList should be(chunks)

    @tailrec def queryChunk(count: Int, buf: DownloadBuffer, bs: ByteString): ByteString = {
      if (count == 0) bs
      else {
        val (optBs, buf2) = buf.fetchData(32)
        queryChunk(count - 1, buf2, bs ++ optBs.get)
      }
    }

    val result = queryChunk(chunks.length, buffer, ByteString.empty)
    result.utf8String should be(FileTestHelper.TestData)
  }

  it should "support querying smaller chunks" in {
    val ChunkSize = 32
    val buffer = DownloadBuffer.empty addChunk ByteString(FileTestHelper.TestData)

    val (optChunk, buf2) = buffer fetchData ChunkSize
    buf2.size should be(FileTestHelper.TestData.length - ChunkSize)
    optChunk.get.utf8String should be(FileTestHelper.TestData.substring(0, ChunkSize))
  }

  it should "support querying multiple smaller chunks" in {
    val ChunkSize = 16
    val buffer = DownloadBuffer.empty addChunk ByteString(FileTestHelper.TestData)
    val (_, buf2) = buffer fetchData ChunkSize

    val (optChunk2, buf3) = buf2 fetchData ChunkSize
    buf3.size should be(FileTestHelper.TestData.length - 2 * ChunkSize)
    optChunk2.get.utf8String should be(FileTestHelper.TestData
      .substring(ChunkSize, 2 * ChunkSize))
  }

  it should "correctly handle requests for larger blocks" in {
    val Data = "Small data"
    val buffer = DownloadBuffer.empty addChunk ByteString(Data)

    val (optChunk, buf2) = buffer fetchData 1000
    optChunk.get.utf8String should be(Data)
    buf2.size should be(0)
  }

  it should "return the head chunk in the chunks list" in {
    val ChunkSize = 64
    val MoreData = "More text"
    val buffer = DownloadBuffer.empty
      .addChunk(ByteString(FileTestHelper.TestData))
      .addChunk(ByteString(MoreData))
    val (_, buf2) = buffer fetchData ChunkSize

    val chunks = buf2.chunks
    val remainingData = chunks.foldLeft(ByteString.empty)(_ ++ _)
    remainingData.utf8String should be(FileTestHelper
      .TestData.substring(ChunkSize) + MoreData)
  }
}
