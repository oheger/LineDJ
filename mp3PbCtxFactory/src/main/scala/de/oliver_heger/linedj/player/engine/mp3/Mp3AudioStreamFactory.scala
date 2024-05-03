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

import de.oliver_heger.linedj.player.engine.AudioStreamFactory
import de.oliver_heger.linedj.player.engine.AudioStreamFactory.AudioStreamPlaybackData

import java.io.InputStream
import javax.sound.sampled.{AudioFormat, AudioInputStream, AudioSystem}

/**
  * An implementation of [[AudioStreamFactory]] that handles MP3 audio streams.
  *
  * This implementation requires additional libraries to be present in the
  * classpath that can encode MP3 audio data.
  */
object Mp3AudioStreamFactory extends AudioStreamFactory:
  /** The expected file extension for audio files supported by this factory. */
  final val FileExtension = "mp3"

  /**
    * Constant for the amount of data that should be available in the audio
    * stream before the audio stream can be obtained.
    */
  private val Mp3StreamFactoryLimit = 1048570

  override def playbackDataFor(uri: String): Option[AudioStreamPlaybackData] =
    if AudioStreamFactory.isFileExtensionIgnoreCase(uri, FileExtension) then
      val playbackData = AudioStreamPlaybackData(streamCreator = createAudioStream,
        streamFactoryLimit = Mp3StreamFactoryLimit)
      Some(playbackData)
    else
      None

  /**
    * Creates an MP3 audio stream for the given input stream. Throws an
    * exception if the format cannot be encoded.
    *
    * @param stream the input stream
    * @return the MP3 audio stream for this input and the format to obtain a 
    *         line
    */
  private def createAudioStream(stream: InputStream): AudioInputStream =
    val in = AudioSystem getAudioInputStream stream
    val baseFormat = in.getFormat
    val decodedFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
      baseFormat.getSampleRate, 16, baseFormat.getChannels, baseFormat.getChannels * 2,
      baseFormat.getSampleRate, false)
    AudioSystem.getAudioInputStream(decodedFormat, in)
