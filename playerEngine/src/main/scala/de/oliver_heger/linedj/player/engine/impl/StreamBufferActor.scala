/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.impl

import java.io.{IOException, InputStream}

import akka.actor.Actor
import akka.util.ByteString
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest, DynamicInputStream}
import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.player.engine.impl.LocalBufferActor.{BufferDataComplete, BufferDataResult}
import de.oliver_heger.linedj.player.engine.impl.StreamBufferActor.{ClearBuffer, FillBuffer, InitStream}

object StreamBufferActor {

  /**
    * A message interpreted by [[StreamBufferActor]] that causes the internal
    * buffer to be cleared. This is necessary for instance after playback had
    * paused for a while: The data in the buffer is older while newly read
    * data may be already from a different song, so that there is a hard switch
    * in playback.
    */
  case object ClearBuffer

  /**
    * An internal message that triggers the initialization of the stream to be
    * buffered. This message is sent by this actor to itself during startup. So
    * it is likely the first message to be processed.
    */
  private case object InitStream

  /**
    * An internal message that triggers a fill operation of the buffer. A
    * single chunk of data is loaded. If there is still capacity left, another
    * message of this type is sent to self.
    */
  private case object FillBuffer

}

/**
  * An actor class that buffers data from a blocking stream.
  *
  * This actor is used for playback of internet radio. It is configured with a
  * stream and a buffer size. From this stream it loads data into the buffer
  * continuously and tries to keep the buffer full if data has been read from
  * it. The main purpose of this actor is to keep the blocking stream
  * access into a separate actor. That way data can be loaded from the stream
  * while it is processed in parallel.
  *
  * As the wrapped stream is an internet radio stream, it is expected to not
  * terminate. If the end of the stream is encountered, this actor throws an
  * ''IOException'' which can be handled using supervision. Exceptions of this
  * type are also thrown when data cannot be read from the stream. Such a
  * condition typically means that the radio stream is currently not available.
  *
  * @param config    the player configuration
  * @param streamRef the reference to the input stream to be buffered
  */
class StreamBufferActor(config: PlayerConfig, streamRef: StreamReference) extends Actor {
  /** The buffer for the read data. */
  private val buffer = new DynamicInputStream

  /** The stream to be buffered. */
  private var stream: InputStream = _

  @scala.throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    self ! InitStream
  }

  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    closeStream()
    super.postStop()
  }

  override def receive: Receive = {
    case InitStream =>
      stream = streamRef.openStream()
      triggerFillBuffer()

    case FillBuffer =>
      if (fillChunk() && remainingCapacity > 0) {
        triggerFillBuffer()
      }

    case ClearBuffer =>
      buffer.clear()
      triggerFillBuffer()

    case PlaybackActor.GetAudioData(length) =>
      val buf = new Array[Byte](math.min(length, buffer.available()))
      buffer read buf
      sender !  BufferDataResult(ByteString(buf))
      triggerFillBuffer()

    case CloseRequest =>
      closeStream()
      sender ! CloseAck(self)
      context become closed
  }

  /**
    * Returns a receive function to be active after the actor has been closed.
    * In this case, message processing is very restricted.
    *
    * @return the receive function for state 'closed'
    */
  def closed: Receive = {
    case PlaybackActor.GetAudioData(_) =>
      sender ! BufferDataComplete
  }

  /**
    * Sends a message to itself to trigger another fill operation. Buffer
    * filling is not done in a single operation, but triggered by messages.
    * This makes it possible to process other messages during buffer filling.
    */
  private def triggerFillBuffer(): Unit = {
    self ! FillBuffer
  }

  /**
    * Fills a chunk of data into the buffer.
    *
    * @return a flag whether data was filled into the buffer
    */
  private def fillChunk(): Boolean = {
    val count = math.min(config.bufferChunkSize, remainingCapacity)
    if (count > 0) {
      val b = new Array[Byte](count)
      val read = stream read b
      if (read < 0) {
        throw new IOException("End of buffered stream reached!")
      }
      buffer append ByteString(b take read)
      true
    } else false
  }

  /**
    * Determines the free capacity in the buffer. If this method returns a
    * value greater than 0, more bytes can be filled into the buffer.
    *
    * @return the remaining capacity in the buffer
    */
  private def remainingCapacity: Int =
    config.inMemoryBufferSize - buffer.available()

  /**
    * Closes the wrapped stream if this has not been done yet.
    */
  private def closeStream(): Unit = {
    if (stream != null) stream.close()
    stream = null
  }
}
