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

package de.oliver_heger.linedj.player.engine.mp3

import java.io.InputStream

import de.oliver_heger.linedj.player.engine.PlaybackContext
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''Mp3PlaybackContextFactory''.
  */
class Mp3PlaybackContextFactorySpec extends AnyFlatSpec with Matchers {
  /**
    * Obtains the input stream to a test file.
    *
    * @param name the name of the test file
    * @return the stream for this test file
    */
  private def stream(name: String): InputStream = getClass.getResourceAsStream("/" + name)

  /**
    * Closes the objects in the specified context.
    *
    * @param context the context
    */
  private def close(context: PlaybackContext): Unit = {
    context.stream.close()
    context.line.close()
  }

  "A Mp3PlaybackContextFactory" should "create a context for a MP3 file" in {
    val factory = new Mp3PlaybackContextFactory
    val FileName = "test.mp3"

    val context = factory.createPlaybackContext(stream(FileName), FileName).get
    context.format should not be null
    context.line should not be null
    context.stream should not be null
    close(context)
  }

  it should "return None for a stream with unsupported format" in {
    val factory = new Mp3PlaybackContextFactory
    val audioStream = stream("test.txt")

    try {
      val context = factory.createPlaybackContext(audioStream, "test.mp3")
      context shouldBe empty
    } finally audioStream.close()
  }

  it should "ignore files with an unsupported file extension" in {
    val factory = new Mp3PlaybackContextFactory
    val audioStream = stream("test.mp3")

    try {
      factory.createPlaybackContext(audioStream, "test.wav") shouldBe empty
    } finally audioStream.close()
  }

  it should "ignore the case of the file extension" in {
    val factory = new Mp3PlaybackContextFactory

    val context = factory.createPlaybackContext(stream("test.mp3"), "test.MP3").get
    close(context)
  }
}
