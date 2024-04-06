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

import de.oliver_heger.linedj.player.engine.stream.LineWriterStage.PlayedAudioChunk
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{ActorAttributes, Attributes, FlowShape, Graph, Inlet, Outlet}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.util.ByteString

import javax.sound.sampled.SourceDataLine
import scala.concurrent.duration.*

object LineWriterStage:
  /**
    * Constant for the default name of the blocking dispatcher that is used for
    * this stage. Here the standard blocking dispatcher from the Pekko Streams
    * configuration is referenced.
    */
  final val BlockingDispatcherName = "pekko.stream.blocking-io-dispatcher"

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
    * the given line. With the given ''close'' flag, it can be controlled
    * whether the line should be closed after stream processing.
    *
    * @param line           the line to write data to
    * @param dispatcherName the name of the dispatcher to be used for this
    *                       stage
    * @param closeLine      flag whether the line should be closed
    * @return the new stage instance
    */
  def apply(line: SourceDataLine,
            dispatcherName: String = BlockingDispatcherName,
            closeLine: Boolean = true): Graph[FlowShape[ByteString, PlayedAudioChunk], NotUsed] =
    new LineWriterStage(line, closeLine).withAttributes(ActorAttributes.dispatcher(dispatcherName))
end LineWriterStage

/**
  * A [[GraphStage]] implementation that feeds audio data into a
  * [[SourceDataLine]], so that audio is played.
  *
  * An instance is initialized with the [[SourceDataLine]] to interact with.
  * This line must be compatible with the audio data received from upstream.
  * The stage writes the data into the line and measures the time for the
  * playback. It produces data that allows keeping track on the audio playback.
  * This data is passed downstream.
  *
  * When the audio data to be played is complete, the line is drained.
  * Optionally, it can be closed as well.
  *
  * Note that the operations against the line are blocking. Therefore, this
  * stage should run on a dedicated dispatcher for blocking operations. The
  * companion object provides a factory function that fulfills this criterion.
  *
  * @param line      the line for audio playback
  * @param closeLine flag whether the stage is responsible for closing the line
  */
class LineWriterStage private(line: SourceDataLine,
                              closeLine: Boolean) extends GraphStage[FlowShape[ByteString, PlayedAudioChunk]]:
  private val in = Inlet[ByteString]("LineWriterStage.in")
  private val out = Outlet[PlayedAudioChunk]("LineWriterStage.out")

  override def shape: FlowShape[ByteString, PlayedAudioChunk] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape):
    setHandler(in, new InHandler:
      override def onPush(): Unit =
        val chunk = grab(in)
        val duration = playAudio(chunk)
        push(out, PlayedAudioChunk(chunk.size, duration))
    )

    setHandler(out, new OutHandler:
      override def onPull(): Unit =
        pull(in)
    )

    override def postStop(): Unit =
      line.drain()
      if closeLine then line.close()
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
    line.write(data.toArray, 0, data.size)
    (System.nanoTime() - startTime).nanos
