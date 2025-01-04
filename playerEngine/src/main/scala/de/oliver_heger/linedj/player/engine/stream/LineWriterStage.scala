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

import de.oliver_heger.linedj.player.engine.AudioStreamFactory
import de.oliver_heger.linedj.player.engine.stream.LineWriterStage.{LineCreatorFunc, PlayedAudioChunk, calculateChunkDuration}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.*
import org.apache.pekko.util.ByteString

import java.util.concurrent.TimeUnit
import javax.sound.sampled.{AudioFormat, AudioSystem, DataLine, SourceDataLine}
import scala.concurrent.duration.*

object LineWriterStage:
  /**
    * Constant for the default name of the blocking dispatcher that is used for
    * this stage. Here the standard blocking dispatcher from the Pekko Streams
    * configuration is referenced.
    */
  final val BlockingDispatcherName = "pekko.stream.blocking-io-dispatcher"

  /**
    * Definition of a function type for creating a [[SourceDataLine]] from the
    * header of an audio stream. This is used to create a line compatible with
    * the audio data to be played.
    */
  type LineCreatorFunc = AudioEncodingStage.AudioStreamHeader => SourceDataLine

  /**
    * The default function to create a [[SourceDataLine]]. This is fully
    * functional.
    */
  val DefaultLineCreatorFunc: LineCreatorFunc =
    header =>
      val info = new DataLine.Info(classOf[SourceDataLine], header.format)
      AudioSystem.getLine(info).asInstanceOf[SourceDataLine]

  /**
    * A data class describing a chunk of audio data that has been played by
    * writing it into the managed line. Instances of this class are the output
    * type of this [[GraphStage]].
    *
    * @param size     the size of the chunk that was written
    * @param duration the playback duration of this chunk
    */
  case class PlayedAudioChunk(size: Int, duration: FiniteDuration)

  /**
    * Creates a new instance of [[LineWriterStage]] that writes audio data into
    * a line obtained from the given [[LineCreatorFunc]]. If no line creator
    * function is given, the resulting stage is just a dummy that passes data
    * through. This is needed for some use cases, e.g. to check whether an 
    * audio source is available.
    *
    * @param optLineCreator the optional function to create the audio line
    * @param dispatcherName the name of the dispatcher to be used for this
    *                       stage
    * @return the new stage instance
    */
  def apply(optLineCreator: Option[LineCreatorFunc],
            dispatcherName: String = BlockingDispatcherName):
  Graph[FlowShape[AudioEncodingStage.AudioData, PlayedAudioChunk], NotUsed] =
    new LineWriterStage(optLineCreator).withAttributes(ActorAttributes.dispatcher(dispatcherName))

  /**
    * Calculates the duration of a chunk of audio data in nanoseconds. This
    * corresponds to the playback duration of one element passed through the
    * stream.
    *
    * @param format the [[AudioFormat]]
    * @return the duration of a chunk in nanoseconds or ''None'' if this cannot
    *         be determined for this [[AudioFormat]]
    */
  private def calculateChunkDuration(format: AudioFormat): Option[FiniteDuration] =
    if format.getFrameRate != AudioSystem.NOT_SPECIFIED && format.getFrameSize !=
      AudioSystem.NOT_SPECIFIED then
      val chunkSize = AudioStreamFactory.audioBufferSize(format)
      Some(math.round(TimeUnit.SECONDS.toNanos(1) * chunkSize / format.getFrameSize / format.getFrameRate).nanos)
    else None
end LineWriterStage

/**
  * A [[GraphStage]] implementation that feeds audio data into a
  * [[SourceDataLine]], so that audio is played.
  *
  * The [[SourceDataLine]] for audio playback is obtained from a provided
  * [[LineCreatorFunc]] based on the audio format reported by the audio 
  * encoding stage. It is possible to pass ''None'' for the line creator
  * function. Then the stage is just a dummy that passes through data without
  * actual audio playback. This is needed for some use cases, e.g. to verify
  * whether an audio source is available.
  *
  * The stage writes the data into the line and measures the time for the
  * playback. It produces data that allows keeping track on the audio playback.
  * This data is passed downstream.
  *
  * When the audio data to be played is complete, the line is drained and 
  * closed.
  *
  * Note that the operations against the line are blocking. Therefore, this
  * stage should run on a dedicated dispatcher for blocking operations. The
  * companion object provides a factory function that fulfills this criterion.
  *
  * @param optLineCreator the optional function for creating the line
  */
class LineWriterStage private(optLineCreator: Option[LineCreatorFunc])
  extends GraphStage[FlowShape[AudioEncodingStage.AudioData, PlayedAudioChunk]]:
  private val in = Inlet[AudioEncodingStage.AudioData]("LineWriterStage.in")
  private val out = Outlet[PlayedAudioChunk]("LineWriterStage.out")

  override def shape: FlowShape[AudioEncodingStage.AudioData, PlayedAudioChunk] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape):
    /** The line to output the audio data if available. */
    private var optLine: Option[SourceDataLine] = None

    /**
      * Stores the duration of a single chunk of audio data. This value is
      * initialized from the header of the audio stream and - if available -
      * used later to determine the playback duration.
      */
    private var optChunkDuration: Option[FiniteDuration] = None

    setHandler(in, new InHandler:
      override def onPush(): Unit =
        grab(in) match
          case header: AudioEncodingStage.AudioStreamHeader =>
            optLine = optLineCreator.map { creator =>
              val line = creator(header)
              line.open(header.format)
              line.start()
              line
            }
            optChunkDuration = calculateChunkDuration(header.format)
            pull(in)
          case chunk: AudioEncodingStage.AudioChunk =>
            val playbackDuration = playAudio(chunk.data)
            val duration = optChunkDuration getOrElse playbackDuration
            push(out, PlayedAudioChunk(chunk.data.size, duration))
    )

    setHandler(out, new OutHandler:
      override def onPull(): Unit =
        pull(in)
    )

    override def postStop(): Unit =
      optLine.foreach { line =>
        line.drain()
        line.close()
      }
      super.postStop()

    /**
      * Plays the given chunk of audio on the owned line and returns the playback
      * duration.
      *
      * @param data the audio data to be played
      * @return the playback duration
      */
    private def playAudio(data: ByteString): FiniteDuration =
      val startTime = System.nanoTime()
      optLine.foreach(_.write(data.toArray, 0, data.size))
      (System.nanoTime() - startTime).nanos
