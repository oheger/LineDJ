/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.player.engine

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.{OptionValues, TryValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import javax.sound.sampled.AudioFormat
import scala.util.{Failure, Success, Using}

/**
  * Test class for functionality provided by the companion object for
  * [[AudioStreamFactory]].
  */
class AudioStreamFactorySpec extends AnyFlatSpec with Matchers with TryValues with OptionValues with MockitoSugar:
  /**
    * Tests whether the given creator can handle the audio stream for the test
    * wav file.
    *
    * @param creator the audio stream creator to test
    */
  private def checkAudioStreamCreator(creator: AudioStreamFactory.AudioStreamCreator): Unit =
    Using(creator(getClass.getResourceAsStream("/test.wav"))) { audioStream =>
      val format = audioStream.getFormat

      format.getChannels should be(2)
      format.getSampleRate should be(44100.0)
      format.getSampleSizeInBits should be(16)
      format.getFrameSize should be(4)
    }.success

  "DefaultAudioStreamCreator" should "return an audio stream for a wav file" in :
    checkAudioStreamCreator(AudioStreamFactory.DefaultAudioStreamCreator)

  "audioBufferSize" should "return the default buffer size if possible" in :
    val format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100.0, 16, 2, 4, 1411, false)

    AudioStreamFactory.audioBufferSize(format) should be(AudioStreamFactory.DefaultAudioBufferSize)

  it should "return a buffer size that covers full frames" in :
    val format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100.0, 16, 2, 17, 1411, false)

    AudioStreamFactory.audioBufferSize(format) should be(4097)

  "isFileExtensionIgnoreCase" should "return false for a non matching extension" in :
    AudioStreamFactory.isFileExtensionIgnoreCase("test.txt", "mp3") shouldBe false

  it should "return true for a matching extension" in :
    AudioStreamFactory.isFileExtensionIgnoreCase("test.mp3", "mp3") shouldBe true

  it should "return true for a matching extension ignoring case" in :
    AudioStreamFactory.isFileExtensionIgnoreCase("test.MP3", "Mp3") shouldBe true

  it should "return false for a file without an extension" in :
    AudioStreamFactory.isFileExtensionIgnoreCase("test", "mp3") shouldBe false

  it should "handle dots in the file name" in :
    AudioStreamFactory.isFileExtensionIgnoreCase("test.sound.mp3", "MP3") shouldBe true

  it should "handle a trailing dot in the file name correctly" in :
    AudioStreamFactory.isFileExtensionIgnoreCase("test.mp3.", "mp3") shouldBe false

  "DefaultAudioStreamFactory" should "return an audio stream creator for a wav file" in :
    val playbackData = DefaultAudioStreamFactory.playbackDataFor("test.wav")

    checkAudioStreamCreator(playbackData.value.streamCreator)

  it should "ignore the file extension of the URI" in :
    val playbackData = DefaultAudioStreamFactory.playbackDataFor("test.what.ever")

    checkAudioStreamCreator(playbackData.value.streamCreator)

  it should "return a correctly initialized playback data instance" in :
    val playbackData = DefaultAudioStreamFactory.playbackDataFor("test.wav").value

    playbackData.streamFactoryLimit should be(2 * AudioStreamFactory.DefaultAudioBufferSize)

  "CompositeAudioStreamFactory" should "return None if no child factory supports the URI" in :
    val AudioFileUri = "myAudioFile.xyz"
    val childFactories: List[AudioStreamFactory] = List(mock, mock, mock)
    childFactories.foreach { factory =>
      when(factory.playbackDataFor(AudioFileUri)).thenReturn(None)
    }

    val factory = new CompositeAudioStreamFactory(childFactories)
    factory.playbackDataFor(AudioFileUri) shouldBe empty

  it should "return the result from the first supporting child factory" in :
    val AudioFileUri = "supportedAudioFile.lala"
    val childFactory1 = mock[AudioStreamFactory]
    val childFactory2 = mock[AudioStreamFactory]
    val childFactory3 = mock[AudioStreamFactory]
    val creator = mock[AudioStreamFactory.AudioStreamCreator]
    val playbackData = AudioStreamFactory.AudioStreamPlaybackData(streamCreator = creator,
      streamFactoryLimit = 17384)
    when(childFactory1.playbackDataFor(AudioFileUri)).thenReturn(None)
    when(childFactory2.playbackDataFor(AudioFileUri)).thenReturn(Some(playbackData))

    val factory = new CompositeAudioStreamFactory(List(childFactory1, childFactory2, childFactory3))
    val optData = factory.playbackDataFor(AudioFileUri)

    optData.value should be(playbackData)
    verifyNoInteractions(childFactory3)

  "AsyncAudioStreamFactory" should "provide a conversion for normal factories" in :
    val AudioFileUri = "someAudioFile.uri"
    val playbackData = AudioStreamFactory.AudioStreamPlaybackData(mock, 8192)
    val factory = mock[AudioStreamFactory]
    when(factory.playbackDataFor(AudioFileUri)).thenReturn(Some(playbackData))

    val futData = factory.playbackDataForAsync(AudioFileUri)

    futData.value should be(Some(Success(playbackData)))

  it should "return a failed future if the URI is not supported" in :
    val AudioFileUri = "unsupported.uri"
    val factory = mock[AudioStreamFactory]
    when(factory.playbackDataFor(AudioFileUri)).thenReturn(None)

    val futData = factory.playbackDataForAsync(AudioFileUri)

    futData.value match
      case Some(Failure(exception: AsyncAudioStreamFactory.UnsupportedUriException)) =>
        exception.uri should be(AudioFileUri)
      case v => fail("Unexpected result: " + v)

  it should "return a failed future if the synchronous factory throws an exception" in:
    val exception = new IllegalStateException("Test exception: Could not create audio stream.")
    val factory = mock[AudioStreamFactory]
    when(factory.playbackDataFor(any())).thenThrow(exception)

    val futData = factory.playbackDataForAsync("someUri")

    futData.value match
      case Some(Failure(e)) =>
        e should be(exception)
      case v => fail("Unexpected result: " + v)
