package de.oliver_heger.splaya.engine;
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.AudioInputStream
import java.io.IOException
import javax.sound.sampled.AudioFormat
import org.slf4j.LoggerFactory

/**
 * A class that manages the objects required during audio playback.
 *
 * This class is used by the actor which actually performs playback. It
 * holds the objects required for playing audio data, like a
 * [[javax.sound.sampled.SourceDataLine]] object or the current audio stream.
 */
case class PlaybackContext(line: SourceDataLine, audioStream: AudioInputStream,
  streamSize: Long, private val bufferSize: Int) {
  /** The logger. */
  val log = LoggerFactory.getLogger(classOf[SourceReaderActor])
/**
   * Returns the format of the wrapped audio stream.
   * @return the audio format
   */
  def format: AudioFormat = audioStream.getFormat

  /**
   * Creates a buffer with a suitable size that can be used for audio playback.
   * @return the playback buffer
   */
  def createPlaybackBuffer() : Array[Byte] = new Array[Byte](bufferSize)

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
