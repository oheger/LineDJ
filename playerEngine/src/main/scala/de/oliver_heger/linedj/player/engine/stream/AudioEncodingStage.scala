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
import de.oliver_heger.linedj.player.engine.AudioStreamFactory
import de.oliver_heger.linedj.player.engine.stream.AudioEncodingStage.{AudioChunk, AudioData, AudioStreamHeader}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.util.ByteString

import java.io.InputStream
import javax.sound.sampled.AudioFormat
import scala.annotation.tailrec

object AudioEncodingStage:
  /**
    * A class defining the header of an audio stream. An instance of this class
    * is sent as very first element of the stream to following stages. The
    * information stored here can be used to set up the infrastructure for 
    * playing audio.
    *
    * @param format the [[AudioFormat]] for the audio data
    */
  case class AudioStreamHeader(format: AudioFormat)

  /**
    * A class defining a chunk of audio data in an audio stream. An instance 
    * includes the raw audio data. After the initial header (an instance of
    * [[AudioStreamHeader]]), all following elements in the stream are objects
    * of this class.
    *
    * @param data the actual audio data
    */
  case class AudioChunk(data: ByteString)

  /**
    * Definition for the type of the objects sent through an audio stream. This
    * is the output type of [[AudioEncodingStage]].
    */
  type AudioData = AudioStreamHeader | AudioChunk
end AudioEncodingStage

/**
  * A [[GraphStage]] implementation that handles the encoding of audio data for
  * audio playback.
  *
  * The stage is configured with a function that creates an encoding stream
  * (such as an ''AudioInputStream'') for a provided stream returning the data
  * from the source. The data passed downstream is read from this encoding
  * stream. It can be fed into a line for audio playback. To enable a dynamic
  * setup of a line, the data format supports a header with a format 
  * description of the audio data that follows. This is reflected in the output
  * type of this stage.
  *
  * The [[AudioEncodingStageConfig]] object passed to the constructor supports
  * some more settings. It can be configured that before creating the stream
  * for encoding, a number of bytes must be available, so that the source audio
  * format can be inspected. Also, reads from the encoding stream can be
  * delayed until a configurable amount of source data is available. Refer to
  * [[AudioEncodingStageConfig]] for all supported configuration options.
  *
  * @param playbackData information how to encode and play the audio data
  */
class AudioEncodingStage(playbackData: AudioStreamFactory.AudioStreamPlaybackData)
  extends GraphStage[FlowShape[ByteString, AudioData]]:
  private val in = Inlet[ByteString]("AudioEncoding.in")
  private val out = Outlet[AudioData]("AudioEncoding.out")

  override def shape: FlowShape[ByteString, AudioData] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape):
      /** A stream serving as buffer for data from upstream. */
      private val dataStream = new DynamicInputStream

      /** The stream implementing the encode operation. */
      private var optEncodedStream: Option[InputStream] = None

      /** The current limit of available data. */
      private var limit = playbackData.streamFactoryLimit

      /**
        * The buffer for reading from the encoder stream. It is created when
        * the audio buffer size can be determined from the audio format to be
        * played.
        */
      private var buffer: Array[Byte] = _

      setHandler(in, new InHandler {
        override def onUpstreamFinish(): Unit =
          dataStream.complete()
          pushRemaining()
          complete(out)

        override def onPush(): Unit =
          dataStream.append(grab(in))

          if dataStream.available() >= limit then
            val (optHeader, stream) = getOrCreateEncodedStream()
            val size = stream.read(buffer)
            if size > 0 then
              emitWithHeader(optHeader, List(AudioChunk(ByteString.fromArray(buffer, 0, size))))
            else
              // A read result of 0 bytes typically means that the input buffer contains invalid data.
              // Clean it, in the hope that further data from upstream can be encoded.
              dataStream.clear()
              if !emitWithHeader(optHeader, Nil) then
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
        val (optHeader, encStream) = getOrCreateEncodedStream()

        @tailrec def readRemaining(chunks: List[AudioChunk]): List[AudioChunk] =
          val size = encStream.read(buffer)
          if size <= 0 then chunks
          else readRemaining(AudioChunk(ByteString.fromArray(buffer, 0, size)) :: chunks)

        val remainingChunks = readRemaining(Nil)
        emitWithHeader(optHeader, remainingChunks.reverse)

      /**
        * Returns the stream for encoding data. Creates it using the factory if
        * it has not yet been obtained. In this case, the function also returns
        * the [[AudioStreamHeader]], which needs to be passed downstream as the
        * first stream element.
        *
        * @return a tuple with the optional header and the encoding stream
        */
      private def getOrCreateEncodedStream(): (Option[AudioStreamHeader], InputStream) =
        optEncodedStream match
          case Some(stream) =>
            (None, stream)
          case None =>
            val stream = playbackData.streamCreator(dataStream)
            val format = stream.getFormat
            buffer = new Array(AudioStreamFactory.audioBufferSize(format))
            limit = buffer.length
            val header = AudioStreamHeader(format)
            optEncodedStream = Some(stream)
            (Some(header), stream)

      /**
        * Emits data handling optional elements. This function emits all the
        * passed in data (header and chunks) that are defined.
        *
        * @param optHeader the optional header
        * @param chunks    the chunks (can be empty)
        * @return a flag whether data was output
        */
      private def emitWithHeader(optHeader: Option[AudioStreamHeader], chunks: List[AudioChunk]): Boolean =
        val output = optHeader.fold(chunks)(_ :: chunks)
        output match
          case _ :: tail if tail.nonEmpty =>
            emitMultiple(out, output)
            true
          case h :: _ =>
            emit(out, h)
            true
          case Nil =>
            false
