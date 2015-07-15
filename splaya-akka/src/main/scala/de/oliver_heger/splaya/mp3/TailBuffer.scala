/*
 * Copyright 2015 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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
package de.oliver_heger.splaya.mp3

import de.oliver_heger.splaya.io.ChannelHandler.ArraySource
import de.oliver_heger.splaya.io.DynamicInputStream

/**
 * A helper class that can be used to obtain a configurable chunk of data at
 * the end of a stream.
 *
 * Some meta data about audio files is stored at the end of a file. Therefore,
 * it is necessary to obtain this data in a convenient way. During meta data
 * extraction the audio file has to be read from start to end anyway. The
 * task of this class is to provide the last block of the given size after the
 * file has been processed.
 *
 * Usage of this class is as follows: For each file to be processed a new
 * instance is created with the block size past to the constructor. Then
 * chunks of data can be added of arbitrary size by calling ''addData()''.
 * When all chunks of data have been passed to the object the ''tail()''
 * method returns the last chunk of the configured size. Note that this
 * block may be smaller than configured if not enough data was added before.
 *
 * Implementation note: This class is not thread-safe.
 *
 * @param size the size of the block to be collected by this object
 */
private class TailBuffer(val size: Int) {
  /**
   * A dynamic stream for collecting results. This stream is used if results
   * smaller than the tail buffer size have to be dealt with.
   */
  private val stream = new DynamicInputStream

  /**
   * Stores an optional array source with sufficient size.
   */
  private var optTailSource: Option[ArraySource] = None

  /**
   * Adds the given chunk of data. Information about the tail (i.e. the last
   * block) is updated accordingly.
   * @param source the array source describing the block of data to be added
   * @return this buffer
   */
  def addData(source: ArraySource): TailBuffer = {
    if (source.length >= size) {
      optTailSource = Some(source)
    } else {
      storeTailSource(trim = false)
      stream append source
      trimStream()
    }
    this
  }

  /**
   * Returns the last chunk of the data processed so far of the configured
   * size. If more data than the configured block size has been processed, the
   * returned array will be of the expected size. Otherwise, it is smaller.
   * @return an array with the last block of data
   */
  def tail(): Array[Byte] = {
    storeTailSource(trim = true)
    val result = new Array[Byte](stream.available())
    stream read result
    result
  }

  /**
   * Trims the stream to the size of the tail buffer. Unneeded leading bytes
   * are removed.
   * @return the number of bytes which have been removed
   */
  private def trimStream(): Long = {
    stream.skip(math.max(stream.available() - size, 0))
  }

  /**
   * Adds the tail source to the stream if it is defined. If desired, the
   * stream can then be trimmed.
   * @param trim flag whether the stream should be trimmed
   */
  private def storeTailSource(trim: Boolean): Unit = {
    optTailSource foreach { s =>
      stream append s
      if (trim) {
        trimStream()
      }
      optTailSource = None
    }
  }
}
