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

package de.oliver_heger.linedj.player.engine.stream

import de.oliver_heger.linedj.io.DynamicInputStream
import de.oliver_heger.linedj.player.engine.stream.AudioEncodingStage.AudioEncodingStageConfig
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.util.ByteString

import java.io.InputStream
import scala.annotation.tailrec

object AudioEncodingStage:
  /**
    * Type definition for a function that returns an encoding stream for a 
    * given input stream. The function is called with the stream containing the
    * audio data to be played. It should return an audio stream with the 
    * correct target audio format.
    */
  type EncoderStreamFactory = InputStream => InputStream

  /**
    * A class defining the configuration settings of [[AudioEncodingStage]].
    *
    * @param streamFactory          the function to obtain the encoded stream
    * @param streamFactoryLimit     the number of bytes that should be
    *                               available before calling the stream
    *                               factory
    * @param encoderStreamLimit     the number of bytes that should be
    *                               available before reading a chunk from the
    *                               encoded stream
    * @param encoderStreamChunkSize the chunk size for reading from the
    *                               encoded stream
    */
  case class AudioEncodingStageConfig(streamFactory: EncoderStreamFactory,
                                      streamFactoryLimit: Int,
                                      encoderStreamLimit: Int,
                                      encoderStreamChunkSize: Int)
end AudioEncodingStage

/**
  * A [[GraphStage]] implementation that handles the encoding of audio data for
  * audio playback.
  *
  * The stage is configured with a function that creates an encoding stream
  * (such as an ''AudioInputStream'') for a provided stream returning the data
  * from the source. The data passed downstream is read from this encoding
  * stream. It can be fed into a line for audio playback.
  *
  * The [[AudioEncodingStageConfig]] object passed to the constructor supports
  * some more settings. It can be configured that before creating the stream
  * for encoding, a number of bytes must be available, so that the source audio
  * format can be inspected. Also, reads from the encoding stream can be
  * delayed until a configurable amount of source data is available. Refer to
  * [[AudioEncodingStageConfig]] for all supported configuration options.
  *
  * @param config the configuration for this stage
  */
class AudioEncodingStage(config: AudioEncodingStageConfig) extends GraphStage[FlowShape[ByteString, ByteString]]:
  private val in = Inlet[ByteString]("AudioEncoding.in")
  private val out = Outlet[ByteString]("AudioEncoding.out")

  override def shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      /** A stream serving as buffer for data from upstream. */
      private val dataStream = new DynamicInputStream

      /** The stream implementing the encode operation. */
      private var optEncodedStream: Option[InputStream] = None

      /** The current limit of available data. */
      private var limit = config.streamFactoryLimit

      /** The buffer for reading from the encoder stream. */
      private val buffer = Array.ofDim[Byte](config.encoderStreamChunkSize)

      setHandler(in, new InHandler {
        override def onUpstreamFinish(): Unit =
          dataStream.complete()
          pushRemaining()
          complete(out)

        override def onPush(): Unit =
          dataStream.append(grab(in))

          if dataStream.available() >= limit then
            val size = encodedStream.read(buffer)
            if size > 0 then
              push(out, ByteString.fromArray(buffer, 0, size))
            else
              // A read result of 0 bytes typically means that the input buffer contains invalid data.
              // Clean it, in the hope that further data from upstream can be encoded.
              dataStream.clear()
              pull(in)
          else
            pull(in)
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit =
          pull(in)
      })

      /**
        * Processes the remaining data after upstream has finished. Now all
        * data in the data stream has to be processed, no matter if limits are
        * fulfilled or not.
        */
      private def pushRemaining(): Unit =
        val encStream = encodedStream

        @tailrec def readRemaining(chunks: List[ByteString]): List[ByteString] =
          val size = encStream.read(buffer)
          if size <= 0 then chunks
          else readRemaining(ByteString.fromArray(buffer, 0, size) :: chunks)

        val remainingChunks = readRemaining(Nil)
        emitMultiple(out, remainingChunks.reverse)

      /**
        * Return the stream for encoding data. Create it using the factory if
        * it has not yet been obtained.
        *
        * @return the encoding stream
        */
      private def encodedStream: InputStream =
        optEncodedStream match
          case Some(stream) =>
            stream
          case None =>
            val stream = config.streamFactory(dataStream)
            optEncodedStream = Some(stream)
            limit = config.encoderStreamLimit
            stream
    }
