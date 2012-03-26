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
 * A message for playing an audio file. The message contains some information
 * about the audio file to be played.
 * @param uri the URI of the source
 * @param index the index of this source in the current playlist
 * @param the length of the source (in bytes)
 */
case class AudioSource(uri: String, index: Int, length: Long) {
  /**
   * Creates a new ''AudioSource'' object based on this instance with the
   * specified length.
   * @param newLength the length of the new source
   * @return the new ''AudioSource''
   */
  def resize(newLength: Long): AudioSource = AudioSource(uri, index, newLength)
}

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
 * A message sent out by the player engine if an error occurs during playback.
 * This can be for instance an error caused by a file which cannot be read, or
 * an error when writing a file. Some errors can be handled by the engine
 * itself, e.g. by just skipping the problematic source. Others cause the whole
 * playback to be aborted.
 */
case class PlaybackError(msg: String, exception: Throwable, fatal: Boolean)

/**
 * A message which tells the playback actor that playback should start now.
 */
case object StartPlayback

/**
 * A message which tells the playback actor that playback should pause.
 */
case object StopPlayback

/**
 * A message indicating that the playback actor starts playback. This message
 * is sent when the playback actor receives a ''StartPlayback'' message.
 */
case object PlaybackStarts

/**
 * A message indicating that the playback actor stops playback. This message is
 * sent when the playback actor receives a ''StopPlayback'' message or reaches
 * the end of the playlist.
 */
case object PlaybackStops

/**
 * A message which tells the playback actor to skip the currently played audio
 * stream.
 */
case object SkipCurrentSource

/**
 * A message sent by the playback actor to all registered listeners if playback
 * of a new audio source starts.
 * @param source the source which is now played
 */
case class PlaybackSourceStart(source: AudioSource)

/**
 * A message sent by the playback actor to all registered listeners if a source
 * has been played completely.
 * @param source the source whose playback has finished
 */
case class PlaybackSourceEnd(source: AudioSource)

/**
 * A message sent by the playback actor whenever the position in the audio
 * stream has changed. This information can be used for instance to implement
 * a progress bar showing the progress in the current audio file.
 * @param audioStreamPosition the current position in the audio stream
 * @param audioStreamLength the length of the audio stream (or -1 if unknown)
 * @param dataStreamPosition the current position in the underlying data stream
 * @param source the current audio source
 */
case class PlaybackPositionChanged(audioStreamPosition: Long,
  audioStreamLength: Long, dataStreamPosition: Long, source: AudioSource) {
  /**
   * Calculates the relative position in the stream (in percent). Depending on
   * the information about the audio stream available, either the relative
   * position in the audio stream is calculated or in the underlying data
   * stream; in the latter case, result may be less exact.
   * @return the relative position in the stream
   */
  def relativePosition: Int = {
    val pos = if (audioStreamLength > 0) (100 * audioStreamPosition) / audioStreamLength
    else (100 * dataStreamPosition / source.length)
    pos.toInt
  }
}

/**
 * A message indicating that the audio player is to be flushed. Flushing means
 * that all actors wipe out their current state so that playback can start
 * anew, at a different position in the playlist.
 */
case object FlushPlayer
