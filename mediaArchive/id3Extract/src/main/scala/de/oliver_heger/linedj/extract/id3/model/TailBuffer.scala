/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.extract.id3.model

import akka.util.ByteString

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
    * A dynamic buffer for collecting results. This buffer is used if results
    * smaller than the tail buffer size have to be dealt with.
    */
  private var buffer = ByteString.empty

  /**
    * Adds the given chunk of data. Information about the tail (i.e. the last
    * block) is updated accordingly.
    *
    * @param data the block of data to be added
    * @return this buffer
    */
  def addData(data: ByteString): TailBuffer = {
    if (data.length >= size) {
      buffer = data
    } else {
      buffer = buffer.takeRight(size - data.length) ++ data
    }
    this
  }

  /**
    * Returns the last chunk of the data processed so far of the configured
    * size. If more data than the configured block size has been processed, the
    * returned byte string will be of the expected size. Otherwise, it is
    * smaller.
    *
    * @return a ''ByteString'' with the last block of data
    */
  def tail(): ByteString = {
    if (buffer.length > size) {
      buffer = buffer takeRight size
    }
    buffer
  }
}
