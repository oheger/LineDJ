package de.oliver_heger.splaya.engine;
import javax.sound.sampled.SourceDataLine
import org.slf4j.LoggerFactory

/**
 * A message that indicates that processing should be aborted.
 */
case class Exit() {
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
case class AddSourceStream(uri: String, index: Int) {
  /**
   * A specialized constructor for creating an instance that does not contain
   * any real data. Such an instance can be used to indicate the end of a
   * playlist.
   */
  def this() = this(null, -1)

  /**
   * A flag whether this instance contains actual data.
   */
  val isDefined = uri != null && index >= 0
}

/**
 * A message which instructs the reader actor to read another chunk copy it to
 * the target location.
 */
case object ReadChunk

/**
 * A message indicating the end of the playlist. After this message was sent to
 * an actor, no more audio streams are accepted.
 */
case object PlaylistEnd

/**
 * A message for writing a chunk of audio data into the specified line.
 * @param line the line
 * @param chunk the array with the data to be played
 * @param len the length of the array (the number of valid bytes)
 * @param currentPos the current position in the source stream
 * @param skipPos the skip position for the current stream
 */
case class PlayChunk(line: SourceDataLine, chunk: Array[Byte], len: Int,
  currentPos: Long, skipPos: Long)

/**
 * A message which indicates that a chunk of audio data was played. The chunk
 * may be written partly; the exact number of bytes written to the data line
 * is contained in the message.
 * @param bytesWritten the number of bytes which has been written
 */
case class ChunkPlayed(bytesWritten: Int)

/**
 * A message sent out by the source reader actor if reading from a source
 * causes an error. This message tells the playback actor that the current
 * source is skipped after this position. Playback can continue with the next
 * source.
 */
case class SourceReadError(bytesRead: Long)

/**
 * A message which tells the playback actor that playback should start now.
 */
case object StartPlayback

/**
 * A message which tells the playback actor that playback should pause.
 */
case object StopPlayback

/**
 * A message which tells the playback actor to skip the currently played audio
 * stream.
 */
case object SkipCurrentSource

/**
 * A message indicating that the audio player is to be flushed. Flushing means
 * that all actors wipe out their current state so that playback can start
 * anew, at a different position in the playlist.
 */
case object FlushPlayer
