/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.stream

import java.io.InputStream
import java.util.concurrent.BlockingQueue
import javax.sound.sampled.{AudioFormat, AudioInputStream}
import scala.collection.mutable.ArrayBuffer

/**
  * An object providing functionality for testing audio streams that is 
  * required for multiple test classes.
  */
object AudioStreamTestHelper:
  /** A format used by the test audio input streams. */
  final val Format = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44.1, 16, 2, 17, 44100.0, false)

  /** The byte used for XOR encoding. */
  private val EncodeByte: Byte = 42

  /**
    * A data class storing information about a read operation. This is used by
    * [[DummyEncoderStream]] to provide information about the reads done by
    * this stream.
    *
    * @param available  the number of bytes available in the source stream
    * @param bufferSize the size of the read buffer
    */
  case class ReadData(available: Int, bufferSize: Int)

  /**
    * Implementation of a stream that performs a dummy encoding based on the
    * ''encodeBytes()'' function. This implementation expects that only the
    * ''read()'' function expecting a byte array is called.
    *
    * @param source    the source stream to read data from
    * @param readQueue a queue to propagate read information
    */
  class DummyEncoderStream(source: InputStream, readQueue: BlockingQueue[ReadData])
    extends AudioInputStream(source, Format, 8192):
    override def read(): Int =
      throw UnsupportedOperationException("Unexpected invocation.")

    override def read(b: Array[Byte]): Int =
      val readData = ReadData(available = source.available(), bufferSize = b.length)
      readQueue.offer(readData)

      val buffer = Array.ofDim[Byte](b.length)
      val size = source.read(buffer)

      if size < 0 then -1
      else
        val data = if size < buffer.length then buffer.take(size) else buffer
        val encodedBytes = encodeBytes(data)
        System.arraycopy(encodedBytes, 0, b, 0, encodedBytes.length)
        encodedBytes.length

    override def read(b: Array[Byte], off: Int, len: Int): Int =
      throw UnsupportedOperationException("Unexpected invocation.")
  end DummyEncoderStream

  /**
    * Simulates an encoding of the given input data.
    *
    * @param data the input data to be encoded
    * @return the resulting array with "encoded" bytes
    */
  def encodeBytes(data: Array[Byte]): Array[Byte] =
    val buf = ArrayBuffer.empty[Byte]
    data.filterNot(_ == 0).foreach { b =>
      buf += (b ^ EncodeByte).toByte
    }
    buf.toArray
