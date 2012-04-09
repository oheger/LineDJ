package de.oliver_heger.splaya.engine;

import scala.actors.Actor
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.LineListener
import javax.sound.sampled.LineEvent

/**
 * An actor that feeds a line for audio playback.
 *
 * This actor receives messages for playing a chunk of audio data. It writes
 * this chunk into the current line. When the chunk has been played completely
 * it sends back a message to the playback actor. This is the signal to load
 * the next chunk of data.
 */
class LineWriteActor extends Actor {
  /**
   * The main message loop of this actor. It processes messages for playing a
   * chunk of audio data. If an ''Exit'' message is received, the main loop
   * ends.
   */
  def act() {
    var running = true

    while (running) {
      receive {
        case ex: Exit =>
          ex.confirmed(this)
          running = false

        case pc: PlayChunk =>
          playAudioChunk(pc)
      }
    }
  }

  /**
   * Returns a string representation for this actor. This string just contains
   * the name of this actor.
   * @return a string for this object
   */
  override def toString = "LineWriteActor"

  /**
   * Plays a chunk of audio data using the given line.
   * @param pc the object with the data about the chunk to be played
   */
  private def playAudioChunk(pc: PlayChunk) {
    val (ofs, len) = handleSkip(pc)
    val played = ofs +
      (if (len <= 0) pc.len
      else pc.line.write(pc.chunk, ofs, len))
    Gateway ! Gateway.ActorPlayback -> ChunkPlayed(played)
  }

  /**
   * Determines the start index and the length to be played from the current
   * data buffer based on the skip position. If parts of the buffer - or even
   * the whole buffer - are to be skipped, index and length are adapted
   * correspondingly.
   * @param pc the data object about the chunk to be played
   * @return a tuple with the adapted offset and length of the data buffer
   */
  private def handleSkip(pc: PlayChunk): Tuple2[Int, Int] = {
    if (pc.skipPos > pc.currentPos + pc.len) {
      (0, 0)
    } else {
      val ofs = scala.math.max(pc.skipPos - pc.currentPos, 0).toInt
      val len = scala.math.max(pc.len - ofs, 0)
      (ofs, len)
    }
  }
}
