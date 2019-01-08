/*
 * Copyright 2015-2019 The Developers Team.
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
import java.net.URL

import akka.util.ByteString
import de.oliver_heger.linedj.io.ChannelHandler.ArraySource

/**
  * A specialized implementation of ''ArraySource'' which is based on another
 * ''ArraySource'' object, but allows overriding the offset and the length.
 *
 * This class is used when a chunk of data received via a reader or source
 * actor has to be adapted.
 *
 * @param data the underlying data array
 * @param length the new length
 * @param offset the new offset
 */
private class ArraySourceImpl(override val data: Array[Byte], override val length: Int,
                              override val offset: Int = 0) extends ArraySource

private object ArraySourceImpl {
  /**
   * Creates a new ''ArraySource'' whose offset and length are adjusted by the
   * given delta. This method can be used if only a subset of the original data
   * is to be processed. The offset may be 0 or negative; then the original
   * source is returned.
   *
   * @param source the original ''ArraySource''
   * @param offsetDelta the start offset of the data to be selected
   * @return the adjusted ''ArraySource''
   */
  def apply(source: ArraySource, offsetDelta: Long): ArraySource =
    if(offsetDelta <= 0) source
    else new ArraySourceImpl(source.data, offset = (source.offset + offsetDelta).toInt,
      length = math.max(source.length - offsetDelta, 0).toInt)

  /**
    * Creates a new ''ArraySource'' from a ''ByteString''. The resulting
    * source contains all data from the byte string.
    *
    * @param bs the ''ByteString''
    * @return the new ''ArraySource''
    */
  def apply(bs: ByteString): ArraySource =
    new ArraySourceImpl(bs.toArray, bs.length)
}

/**
  * A class referencing a stream to be opened. The stream is identified by an
  * URI. The class offers a method for opening it through the
  * ''java.net.URL'' class.
  *
  * @param uri the URI of the referenced stream
  */
private case class StreamReference(uri: String) {
  /**
    * Opens the referenced stream from the URI stored in this class.
    *
    * @return the ''InputStream'' referenced by this object
    * @throws java.io.IOException if an error occurs
    */
  @scala.throws[java.io.IOException] def openStream(): InputStream =
    new URL(uri).openStream()
}
