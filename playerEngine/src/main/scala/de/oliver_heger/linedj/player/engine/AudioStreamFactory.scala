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

import de.oliver_heger.linedj.player.engine.AudioStreamFactory.AudioStreamPlaybackData

import java.io.InputStream
import javax.sound.sampled.{AudioFormat, AudioInputStream, AudioSystem}
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

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
    * A class storing information required to create an [[AudioInputStream]]
    * for an audio source and to play it. Objects of this class are returned by
    * an [[AudioStreamFactory]] implementation when it can handle the source.
    * Based on the information in this class, the encoding of audio data and
    * passing it to a line is possible.
    *
    * @param streamCreator      the function to obtain the encoded stream
    * @param streamFactoryLimit the number of bytes that should be
    *                           available before calling the stream
    *                           creator
    */
  case class AudioStreamPlaybackData(streamCreator: AudioStreamCreator,
                                     streamFactoryLimit: Int)

  /**
    * A default instance for playback data which is returned by the default
    * factory implementation.
    */
  final val DefaultPlaybackData = AudioStreamPlaybackData(streamCreator = DefaultAudioStreamCreator,
    streamFactoryLimit = 2 * DefaultAudioBufferSize)

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
    * A conversion function to treat a regular [[AudioStreamFactory]] as an
    * asynchronous one. The resulting asynchronous factory returns a successful
    * future if the original factory supports the passed in URI. Otherwise, the
    * future fails with an [[AsyncAudioStreamFactory.UnsupportedUriException]]
    * or the original exception thrown by the factory.
    *
    * @return an [[AsyncAudioStreamFactory]] on top of a synchronous one
    */
  given Conversion[AudioStreamFactory, AsyncAudioStreamFactory] = (f: AudioStreamFactory) =>
    (uri: String) => Future.fromTry(Try(f.playbackDataFor(uri)).flatMap {
      case Some(data) => Success(data)
      case None => Failure(new AsyncAudioStreamFactory.UnsupportedUriException(uri))
    })
end AudioStreamFactory

/**
  * A trait defining an object that can create an [[AudioInputStream]] from an
  * input stream with audio data. This may require some encoding of data.
  * The format of the resulting audio stream is then used as basis for audio
  * playback.
  */
trait AudioStreamFactory:
  /**
    * Returns an [[AudioStreamPlaybackData]] instance for a file with the given
    * URI if this URI is supported by this factory implementation. Currently,
    * supported formats are detected based on file extensions. If a data object
    * is returned, it contains all information relevant for playing this audio
    * source.
    *
    * @param uri the uri of the audio file/stream
    * @return an [[Option]] with an [[AudioStreamCreator]] that can create an
    *         audio stream for this audio data
    */
  def playbackDataFor(uri: String): Option[AudioStreamPlaybackData]
end AudioStreamFactory

/**
  * An implementation of [[AudioStreamFactory]] that returns an 
  * [[AudioStreamPlaybackData]] with default properties including an
  * [[AudioStreamCreator]] which solely relies on Java's [[AudioSystem]]. The
  * implementation accepts all kinds of URIs and then delegates to the audio
  * system to obtain an audio stream. Whether this works or not in a concrete
  * case, may depend on the audio codecs installed on the current system.
  */
object DefaultAudioStreamFactory extends AudioStreamFactory:
  override def playbackDataFor(uri: String): Option[AudioStreamPlaybackData] =
    Some(AudioStreamFactory.DefaultPlaybackData)
end DefaultAudioStreamFactory

/**
  * An implementation of [[AudioStreamFactory]] that wraps a number of other
  * [[AudioStreamFactory]] objects. When asked for an 
  * [[AudioStreamPlaybackData]], this implementation queries its child 
  * factories in the given order and returns the first result that is not
  * ''None''. Only if none of the child factories can handle the URL, a 
  * ''None'' result is returned.
  *
  * @param factories a list with the child factories
  */
class CompositeAudioStreamFactory(val factories: List[AudioStreamFactory]) extends AudioStreamFactory:
  override def playbackDataFor(uri: String): Option[AudioStreamPlaybackData] =
    playbackDataFromChildFactories(uri, factories)

  /**
    * Queries the child factories for an [[AudioStreamPlaybackData]] for the 
    * given file URI.
    *
    * @param uri            the URI of the audio file
    * @param childFactories the list of factories to query
    * @return an optional creator from the child factories
    */
  @tailrec private def playbackDataFromChildFactories(uri: String,
                                                      childFactories: List[AudioStreamFactory]):
  Option[AudioStreamPlaybackData] =
    childFactories match
      case h :: t =>
        h.playbackDataFor(uri) match
          case optCreator@Some(_) => optCreator
          case None => playbackDataFromChildFactories(uri, t)
      case Nil =>
        None
end CompositeAudioStreamFactory

object AsyncAudioStreamFactory:
  /**
    * A special exception class that is used by [[AsyncAudioStreamFactory]] to
    * indicate that a passed in URI for audio data is not supported. In this
    * case, the resulting [[Future]] fails with an exception instance of this
    * type.
    *
    * @param uri the URI that is not supported
    */
  class UnsupportedUriException(val uri: String) extends IllegalArgumentException
end AsyncAudioStreamFactory

/**
  * A trait defining an object that can create an [[AudioInputStream]] from an
  * input stream with audio data asynchronously. This is analogously to
  * [[AudioStreamFactory]], but the function returning the audio data returns a
  * [[Future]]. This can be used for more complex operations that could also
  * fail.
  */
trait AsyncAudioStreamFactory:
  /**
    * Returns an [[AudioStreamPlaybackData]] instance asynchronously for a file
    * with the given URI if this URI is supported by this factory
    * implementation. The [[Future]] returned by this function can fail if
    * there was an error when constructing the result. In case the URI is not
    * supported, it fails with the special exception type
    * [[AsyncAudioStreamFactory.UnsupportedUriException]].
    *
    * @param uri the uri of the audio file/stream
    * @return a [[Future]] with an [[AudioStreamCreator]] that can create an
    *         audio stream for this audio data
    */
  def playbackDataForAsync(uri: String): Future[AudioStreamPlaybackData]
end AsyncAudioStreamFactory
