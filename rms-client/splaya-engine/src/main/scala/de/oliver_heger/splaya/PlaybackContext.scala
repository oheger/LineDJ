package de.oliver_heger.splaya;
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.AudioInputStream
import java.io.IOException
import javax.sound.sampled.AudioFormat
import org.slf4j.LoggerFactory
import java.io.InputStream

/**
 * A class that manages the objects required during audio playback.
 *
 * Objects of this class are created by implementations of the
 * [[de.oliver_heger.splaya.PlaylistContextFactory]] interface. They contain
 * all information required for playing audio data, like a
 * [[javax.sound.sampled.SourceDataLine]] object or the current audio stream.
 *
 * @param line the audio line
 * @param audioStream the audio stream to be played
 * @param streamSize the size of the audio stream
 * @param bufferSize the size of the audio buffer for playing single chunks
 */
case class PlaybackContext(line: SourceDataLine,
  private val audioStream: AudioInputStream,
  streamSize: Long, private val bufferSize: Int) {
  /** The logger. */
  val log = LoggerFactory.getLogger(classOf[PlaybackContext])

  /**
   * Returns the format of the wrapped audio stream.
   * @return the audio format
   */
  def format: AudioFormat = audioStream.getFormat

  /**
   * Returns the input stream for playback.
   * @return the playback input stream
   */
  def stream: InputStream = audioStream

  /**
   * Creates a buffer with a suitable size that can be used for audio playback.
   * @return the playback buffer
   */
  def createPlaybackBuffer(): Array[Byte] = new Array[Byte](bufferSize)

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
