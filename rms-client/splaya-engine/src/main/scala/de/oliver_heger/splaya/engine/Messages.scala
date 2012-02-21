package de.oliver_heger.splaya.engine;
import javax.sound.sampled.SourceDataLine
import org.slf4j.LoggerFactory

/**
 * A message that indicates that processing should be aborted.
 */
case class Exit {
  /** The logger. */
  val log = LoggerFactory.getLogger(classOf[Exit])

  /**
   * Records that the specified object has processed the Exit message. This
   * implementation just prints a log message.
   * @param x the affected object
   */
  def confirmed(x: Any) {
    log.info("{} exited.", x)
  }
}

/**
 * A message for adding a stream to be played to the source reader actor.
 */
case class AddSourceStream(uri: String, index: Int)

/**
 * A message which instructs the reader actor to read another chunk copy it to
 * the target location.
 */
case class ReadChunk

/**
 * A message for playing an audio file. The message contains some information
 * about the audio file to be played.
 */
case class AudioSource(uri: String, index: Int, length: Long)

/**
 * A message for writing a chunk of audio data into the specified line.
 */
case class PlayChunk(line: SourceDataLine, chunk: Array[Byte], len: Int)

/**
 * A message which indicates that a full chunk of audio data was played.
 */
case class ChunkPlayed

/**
 * A message sent out by the source reader actor if reading from a source
 * causes an error. This message tells the playback actor that the current
 * source is skipped after this position. Playback can continue with the next
 * source.
 */
case class SourceReadError(bytesRead: Long)

/**
 * A message sent out by the player engine if an error occurs during playback.
 * This can be for instance an error caused by a file which cannot be read, or
 * an error when writing a file. Some errors can be handled by the engine
 * itself, e.g. by just skipping the problematic source. Others cause the whole
 * playback to be aborted.
 */
case class PlaybackError(msg: String, exception: Throwable, fatal: Boolean)
