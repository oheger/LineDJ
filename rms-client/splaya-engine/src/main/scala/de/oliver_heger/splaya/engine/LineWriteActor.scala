package de.oliver_heger.splaya.engine;

import scala.actors.Actor
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.LineListener
import javax.sound.sampled.LineEvent

/**
 * <p>An actor that feeds a line for audio playback.</p>
 * <p>This actor receives messages for playing a chunk of audio data. It writes
 * this chunk into the current line. When the chunk has been played completely
 * it sends back a message to the playback actor. This is the signal to load
 * the next chunk of data.</p>
 */
class LineWriteActor extends Actor {
  def act() {
    var running = true

    while(running) {
      receive {
        case Exit => running = false

        case PlayChunk(line, buf, len, pos, skip) =>
          playAudioChunk(line, buf, len)
      }
    }
  }

  /**
   * Plays a chunk of audio data using the given line.
   * @param line the line
   * @param buf the data to be played
   */
  private def playAudioChunk(line:SourceDataLine, buf:Array[Byte], len: Int) {
    line.write(buf, 0, len)
    Gateway ! Gateway.ActorPlayback -> ChunkPlayed
  }
}
