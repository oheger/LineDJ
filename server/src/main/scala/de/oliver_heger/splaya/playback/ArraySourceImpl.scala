package de.oliver_heger.splaya.playback

import de.oliver_heger.splaya.io.ChannelHandler.ArraySource

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
