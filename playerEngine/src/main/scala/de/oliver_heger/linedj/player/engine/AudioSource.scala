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

import de.oliver_heger.linedj.shared.archive.media.MediaFileID

object AudioSource:
  /**
    * Constant for a length indicating an infinite source. This is used for
    * instance for internet radio streams.
    */
  val InfiniteLength: Long = Long.MaxValue

  /**
    * Constant for a length indicating that the exact length of the source is
    * not (yet) known. When audio data is loaded over the network the final
    * size is only determined at the end of the load operation. This can be
    * expressed using this constant.
    */
  val UnknownLength: Long = -1L

  /**
    * Constant representing an error source. This value is used if an error
    * occurs when copying audio data.
    */
  val ErrorSource = AudioSource("ERROR", 0, 0, 0)

  /**
    * Creates a source with an infinite length for the specified URI. For such
    * sources skip information is irrelevant.
    *
    * @param uri the URI of the source
    * @return the new ''AudioSource''
    */
  def infinite(uri: String): AudioSource = apply(uri, InfiniteLength, 0, 0)

/**
 * A data class describing an audio source to be played by the audio player
 * engine. This class contains some meta data about the source and its position
 * in the current playlist. It is mainly used internally by the engine.
 *
 * @param uri the URI of the source
 * @param length the length of the source (in bytes)
 * @param skip the skip position (i.e. the part of the stream at the beginning
 *             which is to be ignored; actual playback starts after this position)
 * @param skipTime the skip time
 */
case class AudioSource(uri: String, length: Long, skip: Long, skipTime: Long):
  /**
    * Checks whether this is a source with an infinite length.
    *
    * @return '''true''' if the length is infinite, '''false''' otherwise
    */
  def isInfinite: Boolean = length == AudioSource.InfiniteLength

  /**
    * Checks whether the length of this source is not yet known.
    *
    * @return '''true''' if the exact length is unknown; '''false''' otherwise
    */
  def isLengthUnknown: Boolean = length == AudioSource.UnknownLength

/**
 * A data class representing an entry in a playlist.
 *
 * An audio player client maintains a list with objects of this class defining
 * the audio files to be played. The information contained here is sufficient
 * to request actual audio content from a media manager and initiate playback.
 *
 * @param sourceID the ID of the audio source in question
 * @param skip the skip position
 * @param skipTime the skip time
 */
case class AudioSourcePlaylistInfo(sourceID: MediaFileID, skip: Long, skipTime: Long)
