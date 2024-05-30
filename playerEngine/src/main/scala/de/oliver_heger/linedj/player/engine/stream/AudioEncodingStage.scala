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

import javax.sound.sampled.{AudioFormat, AudioInputStream}

object AudioEncodingStage:
  /** The default size of the in-memory buffer used by the stage. */
  final val DefaultInMemoryBufferSize: Int = 2 * 1024 * 1024

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
  * The stage is configured with a data object that includes a function for
  * creating an ''AudioInputStream'' for a provided stream returning the data
  * from the source. The data passed downstream is read from this encoding
  * stream. It can be fed into a line for audio playback. To enable a dynamic
  * setup of a line, the data format supports a header with a format 
  * description of the audio data that follows. This is reflected in the output
  * type of this stage.
  *
  * The stage manages a buffer with audio data obtained from upstream. Only if 
  * this buffer is sufficiently filled (what this means exactly depends on the
  * audio format), data can be read from the ''AudioInputStream'' and passed
  * downstream. The maximum size of this buffer can be configured via a
  * constructor argument.
  *
  * @param playbackData       information how to encode and play the audio data
  * @param inMemoryBufferSize the size of the buffer in memory
  */
class AudioEncodingStage(playbackData: AudioStreamFactory.AudioStreamPlaybackData,
                         inMemoryBufferSize: Int = DefaultInMemoryBufferSize)
  extends GraphStage[FlowShape[ByteString, AudioData]]:
  private val in = Inlet[ByteString]("AudioEncoding.in")
  private val out = Outlet[AudioData]("AudioEncoding.out")

  override def shape: FlowShape[ByteString, AudioData] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape):
      /** A stream serving as buffer for data from upstream. */
      private val dataStream = new DynamicInputStream

      /** The stream implementing the encode operation. */
      private var optEncodedStream: Option[AudioInputStream] = None

      /** The current limit of available data. */
      private var limit = playbackData.streamFactoryLimit

      /**
        * The buffer for reading from the encoder stream. It is created when
        * the audio buffer size can be determined from the audio format to be
        * played.
        */
      private var buffer: Array[Byte] = _

      /**
        * A flag whether the header for the audio stream has already been
        * pushed downstream. This needs to be done once before actual encoded
        * audio data is pushed.
        */
      private var headerPushed = false

      setHandler(in, new InHandler {
        override def onUpstreamFinish(): Unit =
          dataStream.complete()
          pushIfPossible()

        override def onPush(): Unit =
          dataStream.append(grab(in))
          pushIfPossible()
          pullIfPossible()
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit =
          pushIfPossible()
          pullIfPossible()
      })

      /**
        * Checks all conditions for pushing a new element downstream. If these
        * are fulfilled, an element is actually pushed. This function also
        * detects the end of the stream.
        */
      private def pushIfPossible(): Unit =
        if dataStream.completed && dataStream.available() == 0 then
          complete(out)
        else if isAvailable(out) then
          if dataStream.completed || dataStream.available() >= limit then
            val stream = getOrCreateEncodedStream()
            if !headerPushed then
              push(out, AudioStreamHeader(stream.getFormat))
              headerPushed = true
            else
              val size = stream.read(buffer)
              if size > 0 then
                push(out, AudioChunk(ByteString.fromArray(buffer, 0, size)))
              else
                // A read result of 0 bytes typically means that the input buffer contains invalid data.
                // Clean it, in the hope that further data from upstream can be encoded.
                dataStream.clear()

      /**
        * Checks all conditions for pulling a new element from upstream. Here
        * especially the free capacity of the in-memory buffer needs to be
        * checked. If possible, the ''in'' port is pulled.
        */
      private def pullIfPossible(): Unit =
        if !hasBeenPulled(in) && !isClosed(in) then
          if dataStream.available() < inMemoryBufferSize then
            pull(in)

      /**
        * Returns the stream for encoding data. Creates it using the factory if
        * it has not yet been obtained.
        *
        * @return the encoding stream
        */
      private def getOrCreateEncodedStream(): AudioInputStream =
        optEncodedStream match
          case Some(stream) =>
            stream
          case None =>
            val stream = playbackData.streamCreator(dataStream)
            val format = stream.getFormat
            buffer = new Array(AudioStreamFactory.audioBufferSize(format))
            limit = buffer.length
            optEncodedStream = Some(stream)
            stream
