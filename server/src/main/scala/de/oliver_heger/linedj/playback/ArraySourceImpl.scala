/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.playback

import de.oliver_heger.linedj.io.ChannelHandler.ArraySource

/**
 * A specialized implementation of ''ArraySource'' which is based on another
 * ''ArraySource'' object, but allows overriding the offset and the length.
 *
 * This class is used when a chunk of data received via a reader or source
 * actor has to be adapted.
 * @param readResult the underlying ''ReadResult''
 * @param length the new length
 * @param offset the new offset
 */
private class ArraySourceImpl(readResult: ArraySource, override val length: Int, override val
offset: Int = 0) extends ArraySource {
  override val data: Array[Byte] = readResult.data
}

private object ArraySourceImpl {
  /**
   * Creates a new ''ArraySource'' whose offset and length are adjusted by the
   * given delta. This method can be used if only a subset of the original data
   * is to be processed. The offset may be 0 or negative; then the original
   * source is returned.
   * @param source the original ''ArraySource''
   * @param offsetDelta the start offset of the data to be selected
   * @return the adjusted ''ArraySource''
   */
  def apply(source: ArraySource, offsetDelta: Int): ArraySource =
    if(offsetDelta <= 0) source
    else new ArraySourceImpl(source, offset = source.offset + offsetDelta,
      length = math.max(source.length - offsetDelta, 0))
}
