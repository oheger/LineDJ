/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.impl

import java.io.InputStream
import javax.sound.sampled.{AudioFormat, SourceDataLine}

import de.oliver_heger.linedj.player.engine.PlaybackContext
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''PlaybackContext''.
 */
class PlaybackContextSpec extends FlatSpec with Matchers with MockitoSugar {
  /**
   * Creates a test ''PlaybackContext'' instance which uses the specified
   * audio format.
   * @param format the audio format
   * @return the test instance
   */
  private def createContext(format: AudioFormat): PlaybackContext =
    PlaybackContext(line = mock[SourceDataLine], stream = mock[InputStream], format = format)

  /**
   * Creates an ''AudioFormat'' mock object with the specified frame size.
   * @param frameSize the frame size
   * @return the mock audio format
   */
  private def createFormat(frameSize: Int): AudioFormat = {
    val format = mock[AudioFormat]
    when(format.getFrameSize).thenReturn(frameSize)
    format
  }

  "A PlaybackContext" should "use a default buffer size if possible" in {
    val context = createContext(createFormat(16))
    context.bufferSize should be(PlaybackContext.DefaultAudioBufferSize)
  }

  it should "adapt the buffer size if necessary" in {
    val context = createContext(createFormat(17))
    context.bufferSize should be(4097)
  }
}
