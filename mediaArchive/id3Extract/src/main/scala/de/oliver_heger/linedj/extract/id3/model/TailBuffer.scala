/*
 * Copyright 2015-2018 The Developers Team.
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
  * Usage of this class is as follows: Starting with an initial, empty
  * instance, updated instances can be created by calling ''addData()''
  * passing in chunks of arbitrary data. To obtain a trimmed result, the
  * ''tail()'' method has to be invoked. Note that the resulting block may be
  * smaller than configured if not enough data was added before.
  *
  * @param size   the size of the block to be collected by this object
  * @param buffer the buffer with current data managed by this instance
  */
case class TailBuffer(size: Int, buffer: ByteString = ByteString.empty) {
  /**
    * Returns an updated instance of ''TailBuffer'' with the passed in block
    * of data added to the internal state.
    *
    * @param data the block of data to be added
    * @return the updated buffer
    */
  def addData(data: ByteString): TailBuffer =
    copy(buffer = if (data.length >= size) data
    else buffer.takeRight(size - data.length) ++ data)

  /**
    * Returns a trimmed ''ByteString'' for the last block of data for the
    * configured buffer size. While the ''buffer'' property may contain more
    * data, this method returns the correct tail buffer of the expected size
    * (or a smaller one if the amount of data provided has been smaller than
    * the configured size).
    *
    * @return a ''ByteString'' with the last block of data
    */
  def tail(): ByteString =
    buffer takeRight size
}
