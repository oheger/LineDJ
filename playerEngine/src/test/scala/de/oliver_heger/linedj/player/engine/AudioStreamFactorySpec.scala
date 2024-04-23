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

import org.scalatest.TryValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.sampled.AudioFormat
import scala.util.Using

/**
  * Test class for functionality provided by the companion object for
  * [[AudioStreamFactory]].
  */
class AudioStreamFactorySpec extends AnyFlatSpec with Matchers with TryValues:
  "DefaultAudioStreamCreator" should "return an audio stream for a wav file" in :
    Using(AudioStreamFactory.DefaultAudioStreamCreator(getClass.getResourceAsStream("/test.wav"))) { audioStream =>
      val format = audioStream.getFormat

      format.getChannels should be(2)
      format.getSampleRate should be(44100.0)
      format.getSampleSizeInBits should be(16)
      format.getFrameSize should be(4)
    }.success

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
