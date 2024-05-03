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

package de.oliver_heger.linedj.player.engine.mp3

import org.scalatest.{OptionValues, TryValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import javax.sound.sampled.AudioFormat
import scala.util.Using

/**
  * Test class for [[Mp3AudioStreamFactory]].
  */
class Mp3AudioStreamFactorySpec extends AnyFlatSpec with Matchers with OptionValues with TryValues:
  /**
    * Checks whether a stream creator for handling MP3 audio data is returned.
    *
    * @param uri the URI to be passed to the factory
    */
  private def checkAudioStreamCreator(uri: String): Unit =
    val optCreator = Mp3AudioStreamFactory.audioStreamCreatorFor(uri)
    Using(optCreator.value(getClass.getResourceAsStream("/test.mp3"))) { stream =>
      val format = stream.getFormat
      format.getEncoding should be(AudioFormat.Encoding.PCM_SIGNED)
      format.getSampleSizeInBits should be(16)
      format.getSampleRate should be(16000.0)
      format.getChannels should be(1)
    }.success

  "Mp3AudioStreamFactory" should "return a stream creator that can deal with MP3 files" in :
    checkAudioStreamCreator("test.mp3")

  it should "return None for an unsupported file extension" in :
    Mp3AudioStreamFactory.audioStreamCreatorFor("test.mp4") shouldBe empty

  it should "ignore case in the file extension" in :
    checkAudioStreamCreator("test.Mp3")
