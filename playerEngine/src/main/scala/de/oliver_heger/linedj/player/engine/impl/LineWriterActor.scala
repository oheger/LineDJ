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

package de.oliver_heger.linedj.player.engine.impl

import javax.sound.sampled.SourceDataLine

import akka.actor.Actor
import de.oliver_heger.linedj.io.ChannelHandler.ArraySource
import de.oliver_heger.linedj.player.engine.impl.LineWriterActor.{AudioDataWritten, DrainLine, LineDrained, WriteAudioData}

/**
 * Companion object for ''LineWriterActor''.
 */
object LineWriterActor {

  /**
    * A message which triggers the playback of audio data.
    *
    * This message causes the ''LineWriterActor'' actor to write the
    * specified data into the given line.
    *
    * @param line the line
    * @param data the data to be written
    */
  case class WriteAudioData(line: SourceDataLine, data: ArraySource)

  /**
    * A message that tells a [[LineWriterActor]] to invoke the ''drain()''
    * method on the specified data line.
    *
    * This message is called at the end of the playback of an audio source
    * before the line is closed. It makes sure that even the very end of the
    * source gets played.
    *
    * @param line the line
    */
  case class DrainLine(line: SourceDataLine)

  /**
    * A message sent by ''LineWriterActor'' when a chunk of audio data has been
    * written into a line.
    *
    * This message can be interpreted as a signal that the actor is now
    * available to process further audio data. Also, some information about the
    * chunk that just have been written is provided.
    *
    * @param chunkLength the length of the data chunk that has been written
    * @param duration the (approximated) duration of the chunk of data
    */
  case class AudioDataWritten(chunkLength: Int, duration: Long)

  /**
    * A message sent by [[LineWriterActor]] after a drain operation has been
    * completed. When this message is received by a client actor it can be
    * sure that there is no more pending audio data for playback and close the
    * line.
    */
  case object LineDrained
}

/**
 * An actor that writes chunks of audio data into a ''SourceDataLine'' object.
 *
 * This actor is used to actually pass audio data to the Java sound system.
 * It accepts ''WriteAudioData'' messages for the audio data to be written
 * which contain both the data to be written and the line which receives this
 * data. Note that writing data into a ''SourceDataLine'' is a blocking
 * operation! Unfortunately, the events sent by the line object are not
 * reliable; in contrast to the documentation, no STOP event is generated when
 * all data has been consumed by the line.
 *
 * When all data has been written the actor answers with an
 * ''AudioDataWritten'' message.
 */
class LineWriterActor extends Actor {
  override def receive: Receive = {
    case WriteAudioData(line, data) =>
      val startTime = System.nanoTime()
      line.write(data.data, data.offset, data.length)
      sender ! AudioDataWritten(data.length, System.nanoTime() - startTime)

    case DrainLine(line) =>
      line.drain()
      sender ! LineDrained
  }
}
