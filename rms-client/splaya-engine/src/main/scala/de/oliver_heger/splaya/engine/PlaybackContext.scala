package de.oliver_heger.splaya.engine;
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.AudioInputStream
import java.io.IOException
import javax.sound.sampled.AudioFormat
import org.slf4j.LoggerFactory

/**
 * <p>A class that manages the objects required during audio playback.</p>
 * <p>This class is used by the actor which actually performs playback. It
 * holds the objects required for playing audio data, like a {@code Line} object
 * or the current audio stream.</p>
 */
case class PlaybackContext(line: SourceDataLine, audioStream: AudioInputStream,
  streamSize: Long) {
  /** The logger. */
  val log = LoggerFactory.getLogger(classOf[SourceReaderActor])

  /**
   * Returns the format of the wrapped audio stream.
   * @return the audio format
   */
  def format: AudioFormat = audioStream.getFormat

  /**
   * Closes all involved objects, freeing all resources. This method must be
   * called when playback of an audio stream ends.
   */
  def close() {
    line.close()
    try {
      audioStream.close()
    } catch {
      case ioex: IOException => log.warn("Error when closing audio stream!", ioex)
    }
  }
}
