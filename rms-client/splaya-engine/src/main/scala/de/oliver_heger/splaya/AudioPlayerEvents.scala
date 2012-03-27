package de.oliver_heger.splaya

/**
 * A message sent out by the player engine if an error occurs during playback.
 * This can be for instance an error caused by a file which cannot be read, or
 * an error when writing a file. Some errors can be handled by the engine
 * itself, e.g. by just skipping the problematic source. Others cause the whole
 * playback to be aborted.
 * @param msg an error message describing the problem
 * @param exception the underlying exception
 * @param fatal '''true''' if this error causes playback to be aborted,
 * '''false''' if playback can be continued
 */
case class PlaybackError(msg: String, exception: Throwable, fatal: Boolean)

/**
 * A message indicating that playback starts. This message is sent when the
 * audio engine is asked to start (or resume) playback.
 */
case object PlaybackStarts

/**
 * A message indicating that playback is stopped. This message is sent when the
 * audio engine is told to stop (or pause) playback or if the end of the
 * playlist is reached.
 */
case object PlaybackStops

/**
 * A data class describing an audio source to be played by the audio player
 * engine. This class contains some meta data about the source and its position
 * in the current playlist.
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
 * A message indicating that playback of a new audio source starts.
 * @param source the source which is now played
 */
case class PlaybackSourceStart(source: AudioSource)

/**
 * A message indicating that a source has been played completely.
 * @param source the source whose playback has finished
 */
case class PlaybackSourceEnd(source: AudioSource)

/**
 * A message sent by the audio engine whenever the position in the current audio
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
