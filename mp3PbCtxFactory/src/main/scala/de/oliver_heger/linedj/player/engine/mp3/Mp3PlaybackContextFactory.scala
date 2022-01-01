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

package de.oliver_heger.linedj.player.engine.mp3

import java.io.InputStream
import java.util.Locale
import javax.sound.sampled.DataLine.Info
import javax.sound.sampled._

import de.oliver_heger.linedj.player.engine.{PlaybackContext, PlaybackContextFactory}
import org.slf4j.LoggerFactory

/**
  * A specialized ''PlaybackContextFactory'' implementation that can handle MP3
  * files.
  */
class Mp3PlaybackContextFactory extends PlaybackContextFactory {
  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

  /**
    * @inheritdoc This implementation checks whether the URI has a supported
    *             file extension. If this is the case, a playback context is
    *             created.
    */
  override def createPlaybackContext(stream: InputStream, uri: String): Option[PlaybackContext] =
    if (isSupportedFileExtension(uri)) setUpContext(stream, uri)
    else None

  /**
    * Creates the playback context for a supported audio file. This method is
    * called after the main method has successfully checked the file URI.
    *
    * @param stream the stream with the audio data
    * @param uri    the URI pointing to the file to be played
    * @return an optional ''PlaybackContext'' for this audio file
    */
  private def setUpContext(stream: InputStream, uri: String): Option[PlaybackContext] = {
    try {
      val in = AudioSystem getAudioInputStream stream
      val baseFormat = in.getFormat
      val decodedFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
        baseFormat.getSampleRate, 16, baseFormat.getChannels, baseFormat.getChannels * 2,
        baseFormat.getSampleRate, false)
      val din = AudioSystem.getAudioInputStream(decodedFormat, in)
      val info = new Info(classOf[SourceDataLine], decodedFormat)
      val line = AudioSystem.getLine(info).asInstanceOf[SourceDataLine]
      Some(PlaybackContext(decodedFormat, din, line))
    } catch {
      case e: Exception =>
        log.error("Could not create PlaybackContext for file " + uri, e)
        None
    }
  }

  /**
    * Checks whether the URI has a supported file extension. If this method
    * returns '''false''', the input file is rejected.
    *
    * @param uri the URI
    * @return a flag whether this URI is supported
    */
  private def isSupportedFileExtension(uri: String): Boolean = {
    uri.toLowerCase(Locale.ENGLISH).endsWith(".mp3")
  }
}
