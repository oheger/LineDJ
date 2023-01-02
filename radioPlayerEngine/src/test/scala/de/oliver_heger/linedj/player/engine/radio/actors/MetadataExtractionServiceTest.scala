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

package de.oliver_heger.linedj.player.engine.radio.actors

import akka.util.ByteString
import de.oliver_heger.linedj.player.engine.radio.actors.RadioStreamTestHelper.dataBlock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object MetadataExtractionServiceTest {
  /** The chunk size for audio data. */
  private val AudioChunkSize = 256

  /** Constant for an initial extraction state. */
  private val InitialState = MetadataExtractionState.initial(AudioChunkSize)

  /**
    * Generates a byte string for the start of a block with metadata. The first
    * byte is a length indicator; then test data with the specified block size
    * is generated.
    *
    * @param lengthByte the length indicator byte
    * @param blockSize  the size of data to generate
    * @return the block with metadata
    */
  private def metadataBlock(lengthByte: Byte, blockSize: Int, index: Int = 0): ByteString =
    RadioStreamTestHelper.metadataBlock(lengthByte, dataBlock(blockSize, index))

  /**
    * Convenience method to execute a ''State'' object to produce the updated
    * state and an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @tparam A the type of the additional result
    * @return a tuple with the updated state and the additional result
    */
  private def updateState[A](s: SupportedMetadataExtractionService.StateUpdate[A],
                             oldState: MetadataExtractionState = InitialState):
  (MetadataExtractionState, A) = s(oldState)

  /**
    * Convenience method to modify a ''State'' object. This means, the state is
    * only manipulated without producing an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @return the updated state
    */
  private def modifyState(s: SupportedMetadataExtractionService.StateUpdate[Unit],
                          oldState: MetadataExtractionState = InitialState): MetadataExtractionState = {
    val (next, _) = updateState(s, oldState)
    next
  }
}

/**
  * Test class for the different services to extract metadata.
  */
class MetadataExtractionServiceTest extends AnyFlatSpec with Matchers {

  import MetadataExtractionServiceTest._

  "MetadataExtractionState" should "create an initial object" in {
    InitialState.audioChunkSize should be(AudioChunkSize)
    InitialState.inMetadata shouldBe false
    InitialState.currentChunkSize should be(AudioChunkSize)
    InitialState.bytesReceived should be(0)
    InitialState.audioChunks shouldBe empty
    InitialState.metadataChunk shouldBe empty
  }

  "SupportedMetadataExtractionService" should "update the state for a smaller block of audio data" in {
    val BlockSize = AudioChunkSize - 10
    val data = dataBlock(BlockSize)

    val state = modifyState(SupportedMetadataExtractionService.dataReceived(data))

    state.audioChunkSize should be(AudioChunkSize)
    state.inMetadata shouldBe false
    state.currentChunkSize should be(AudioChunkSize)
    state.bytesReceived should be(BlockSize)
    state.audioChunks should contain only data
    state.metadataChunk shouldBe empty
  }

  it should "update existing data about audio data" in {
    val BlockSize = 100
    val existingData = dataBlock(BlockSize)
    val newData = dataBlock(BlockSize, index = 1)
    val originalState = InitialState.copy(bytesReceived = BlockSize, audioChunks = List(existingData))

    val state = modifyState(SupportedMetadataExtractionService.dataReceived(newData), originalState)

    state.audioChunkSize should be(AudioChunkSize)
    state.inMetadata shouldBe false
    state.currentChunkSize should be(AudioChunkSize)
    state.bytesReceived should be(2 * BlockSize)
    state.audioChunks should contain theSameElementsInOrderAs List(newData, existingData)
    state.metadataChunk shouldBe empty
  }

  it should "update the state for the last block of audio data" in {
    val data = ByteString(RadioStreamTestHelper.refData(AudioChunkSize))

    val state = modifyState(SupportedMetadataExtractionService.dataReceived(data))

    state.bytesReceived should be(AudioChunkSize)
    state.audioChunks should contain only data
    state.inMetadata shouldBe false
  }

  it should "switch to metadata after receiving a full chunk of audio data" in {
    val MetadataSize = 64
    val BlockSize = 40
    val newData = metadataBlock(4, BlockSize)
    val originalState = InitialState.copy(bytesReceived = AudioChunkSize,
      audioChunks = List(dataBlock(AudioChunkSize)))

    val state = modifyState(SupportedMetadataExtractionService.dataReceived(newData), originalState)

    state.inMetadata shouldBe true
    state.audioChunks should be(originalState.audioChunks)
    state.bytesReceived should be(BlockSize)
    state.currentChunkSize should be(MetadataSize)
    state.metadataChunk should be(dataBlock(BlockSize))
  }

  it should "compute the correct length of a metadata block" in {
    val block = metadataBlock(-1, 128)
    val originalState = InitialState.copy(bytesReceived = AudioChunkSize)

    val state = modifyState(SupportedMetadataExtractionService.dataReceived(block), originalState)

    state.currentChunkSize should be(255 * 16)
  }

  it should "update metadata from a block of data" in {
    val existingData = dataBlock(64)
    val newData = dataBlock(64, index = 1)
    val originalState = InitialState.copy(inMetadata = true, metadataChunk = existingData,
      bytesReceived = existingData.size, currentChunkSize = 2048)

    val state = modifyState(SupportedMetadataExtractionService.dataReceived(newData), originalState)

    state.inMetadata shouldBe true
    state.bytesReceived should be(existingData.size + newData.size)
    state.metadataChunk should be(dataBlock(128))
  }

  it should "switch to audio data after receiving a full chunk of metadata" in {
    val originalState = InitialState.copy(inMetadata = true, currentChunkSize = 1024, bytesReceived = 1024,
      metadataChunk = ByteString("test metadata"))
    val BlockSize = AudioChunkSize / 2
    val newData = dataBlock(BlockSize)

    val state = modifyState(SupportedMetadataExtractionService.dataReceived(newData), originalState)

    state.inMetadata shouldBe false
    state.currentChunkSize should be(AudioChunkSize)
    state.metadataChunk should be(originalState.metadataChunk)
    state.bytesReceived should be(BlockSize)
    state.audioChunks should contain only newData
  }

  it should "handle multiple chunks of data in a single data block" in {
    val AudioBlockSize = AudioChunkSize / 2
    val existingData = dataBlock(AudioBlockSize)
    val audioBlock2 = dataBlock(AudioBlockSize, index = 1)
    val metadata = metadataBlock(4, 64, index = 42)
    val audioBlock3 = dataBlock(AudioBlockSize, index = 2)
    val originalState = InitialState.copy(bytesReceived = AudioBlockSize, audioChunks = List(existingData))

    val state = modifyState(SupportedMetadataExtractionService.dataReceived(audioBlock2 ++ metadata ++ audioBlock3),
      originalState)

    state.inMetadata shouldBe false
    state.currentChunkSize should be(AudioChunkSize)
    state.bytesReceived should be(AudioBlockSize)
    state.metadataChunk should be(dataBlock(64, index = 42))
    state.audioChunks should contain theSameElementsInOrderAs List(audioBlock3, audioBlock2, existingData)
  }

  it should "handle metadata chunks of size 0" in {
    val AudioBlockSize = AudioChunkSize / 2
    val existingData = dataBlock(AudioBlockSize)
    val audioBlock2 = dataBlock(AudioBlockSize, index = 1)
    val audioBlock3 = dataBlock(AudioBlockSize, index = 2)
    val metadata = ByteString(Array[Byte](0))
    val originalState = InitialState.copy(bytesReceived = AudioBlockSize, audioChunks = List(existingData))

    val state = modifyState(SupportedMetadataExtractionService.dataReceived(audioBlock2 ++ metadata ++ audioBlock3),
      originalState)

    state.inMetadata shouldBe false
    state.currentChunkSize should be(AudioChunkSize)
    state.bytesReceived should be(AudioBlockSize)
    state.metadataChunk should be(ByteString.empty)
    state.audioChunks should contain theSameElementsInOrderAs List(audioBlock3, audioBlock2, existingData)
  }

  it should "keep only the latest metadata in a single block" in {
    val MetadataBlockSize = 64
    val MetadataLengthByte: Byte = 4
    val audioBlock1 = dataBlock(AudioChunkSize)
    val audioBlock2 = dataBlock(AudioChunkSize, index = 1)
    val metadataBlock1 = metadataBlock(MetadataLengthByte, MetadataBlockSize)
    val metadataBlock2 = metadataBlock(MetadataLengthByte, MetadataBlockSize, index = 1)
    val data = audioBlock1 ++ metadataBlock1 ++ audioBlock2 ++ metadataBlock2

    val state = modifyState(SupportedMetadataExtractionService.dataReceived(data))

    state.audioChunks should contain theSameElementsInOrderAs List(audioBlock2, audioBlock1)
    state.metadataChunk should be(dataBlock(MetadataBlockSize, index = 1))
  }

  it should "extract data related to audio chunks" in {
    val audioBlock1 = dataBlock(AudioChunkSize)
    val audioBlock2 = dataBlock(AudioChunkSize / 2, index = 3)
    val originalState = InitialState.copy(audioChunks = List(audioBlock2, audioBlock1))

    val (state, extracted) = updateState(SupportedMetadataExtractionService.extractedData(), originalState)

    state.audioChunks shouldBe empty
    extracted.audioChunks should contain theSameElementsInOrderAs List(audioBlock1, audioBlock2)
    extracted.metadataChunk shouldBe empty
  }

  it should "extract data related to metadata if audio processing is again active" in {
    val metadata = dataBlock(128)
    val originalState = InitialState.copy(metadataChunk = metadata)

    val (state, extracted) = updateState(SupportedMetadataExtractionService.extractedData(), originalState)

    state.metadataChunk shouldBe empty
    extracted.metadataChunk should be(Some(metadata))
  }

  it should "not extract an incomplete metadata chunk" in {
    val metadata = dataBlock(64)
    val originalState = InitialState.copy(inMetadata = true, currentChunkSize = 128, bytesReceived = 64,
      metadataChunk = metadata)

    val (state, extracted) = updateState(SupportedMetadataExtractionService.extractedData(), originalState)

    state.metadataChunk should be(metadata)
    extracted.metadataChunk shouldBe empty
  }

  it should "extract an already completed metadata chunk" in {
    val metadata = dataBlock(128)
    val originalState = InitialState.copy(inMetadata = true, currentChunkSize = 128, bytesReceived = 128,
      metadataChunk = metadata)

    val (state, extracted) = updateState(SupportedMetadataExtractionService.extractedData(), originalState)

    state.metadataChunk shouldBe empty
    extracted.metadataChunk should be(Some(metadata))
  }

  it should "handle an incoming block of data" in {
    val audioBlock1 = dataBlock(AudioChunkSize)
    val audioBlock2 = dataBlock(AudioChunkSize / 2, index = 3)
    val metadata = dataBlock(256, index = 32)
    val originalState = InitialState.copy(audioChunks = List(audioBlock1), inMetadata = true,
      currentChunkSize = metadata.size, bytesReceived = metadata.size, metadataChunk = metadata)

    val (state, extracted) = updateState(SupportedMetadataExtractionService.handleData(audioBlock2), originalState)

    state.inMetadata shouldBe false
    state.audioChunks shouldBe empty
    state.bytesReceived should be(audioBlock2.size)
    state.metadataChunk shouldBe empty
    extracted.audioChunks should contain theSameElementsInOrderAs List(audioBlock1, audioBlock2)
    extracted.metadataChunk should be(Some(metadata))
  }

  "UnsupportedMetadataExtractionService" should "process a block of data" in {
    val data = dataBlock(AudioChunkSize + 11)

    val state = modifyState(UnsupportedMetadataExtractionService.dataReceived(data))

    state.inMetadata shouldBe false
    state.currentChunkSize should be(InitialState.currentChunkSize)
    state.bytesReceived should be(0)
    state.metadataChunk shouldBe empty
    state.audioChunks should contain only data
  }

  it should "update audio chunks when a new block of data arrives" in {
    val audioBlock1 = dataBlock(AudioChunkSize)
    val audioBlock2 = dataBlock(AudioChunkSize, index = 1)
    val originalState = InitialState.copy(audioChunks = List(audioBlock1))

    val state = modifyState(UnsupportedMetadataExtractionService.dataReceived(audioBlock2), originalState)

    state.audioChunks should contain theSameElementsInOrderAs List(audioBlock2, audioBlock1)
  }

  it should "extract all available audio data" in {
    val audioBlock1 = dataBlock(AudioChunkSize)
    val audioBlock2 = dataBlock(AudioChunkSize, index = 1)
    val originalState = InitialState.copy(audioChunks = List(audioBlock2, audioBlock1),
      metadataChunk = ByteString("Some metadata"), bytesReceived = 4711, currentChunkSize = 8080)

    val (state, extracted) = updateState(UnsupportedMetadataExtractionService.extractedData(), originalState)

    state should be(originalState.copy(audioChunks = List.empty))
    extracted.audioChunks should contain theSameElementsInOrderAs List(audioBlock1, audioBlock2)
    extracted.metadataChunk shouldBe empty
  }
}
