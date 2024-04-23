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

import de.oliver_heger.linedj.player.engine.AudioStreamFactory.AudioStreamCreator

import java.io.InputStream
import javax.sound.sampled.{AudioFormat, AudioInputStream, AudioSystem}

object AudioStreamFactory:
  /**
    * Definition of a function type that converts a regular [[InputStream]] to
    * an [[AudioInputStream]].
    */
  type AudioStreamCreator = InputStream => AudioInputStream

  /**
    * A default [[AudioStreamCreator]] function that uses Java's
    * [[AudioSystem]] class to obtain an [[AudioInputStream]] for a given
    * stream. This implementation works if the passed in stream contains audio
    * data that can be processed by the Java audio system out of the box.
    */
  final val DefaultAudioStreamCreator: AudioStreamCreator = AudioSystem.getAudioInputStream

  /** Constant for the default audio buffer size. */
  final val DefaultAudioBufferSize = 4096

  /**
    * Calculates the size of a buffer for playing audio data of the given
    * format. The buffer size is determined based on the frame size; it is
    * ensured that full frames fit into the buffer.
    *
    * @param format the [[AudioFormat]]
    * @return the size of a buffer for playing audio of this format
    */
  def audioBufferSize(format: AudioFormat): Int =
    if DefaultAudioBufferSize % format.getFrameSize != 0 then
      ((DefaultAudioBufferSize / format.getFrameSize) + 1) * format.getFrameSize
    else DefaultAudioBufferSize

  /**
    * Checks whether the given URI strings ends with the given extension
    * ignoring case.
    *
    * @param uri       the URI
    * @param extension the extension to test (without a leading dot)
    * @return a flag whether the extension is matched
    */
  def isFileExtensionIgnoreCase(uri: String, extension: String): Boolean =
    val posExtension = uri.lastIndexOf('.')
    if posExtension < 0 then false
    else uri.substring(posExtension + 1).equalsIgnoreCase(extension)

/**
  * A trait defining an object that can create an [[AudioInputStream]] from an
  * input stream with audio data. This may require some encoding of data.
  * The format of the resulting audio stream is then used as basis for audio
  * playback.
  */
trait AudioStreamFactory:
  /**
    * Returns an [[AudioStreamCreator]] for a file with the given URI if this
    * URI is supported by this factory implementation. Currently, supported
    * formats are detected based on file extensions.
    *
    * @param uri the uri of the audio file/stream
    * @return an [[Option]] with an [[AudioStreamCreator]] that can create an
    *         audio stream for this audio data
    */
  def audioStreamCreatorFor(uri: String): Option[AudioStreamCreator]
