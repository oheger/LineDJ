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
 * A message indicating the end of the playlist. This message is sent after all
 * audio sources in the current playlist have been played.
 */
case object PlaylistEnd

/**
 * A data class describing an audio source to be played by the audio player
 * engine. This class contains some meta data about the source and its position
 * in the current playlist.
 * @param uri the URI of the source
 * @param index the index of this source in the current playlist
 * @param length the length of the source (in bytes)
 * @param skip the skip position (i.e. the part of the stream at the beginning
 * which is to be ignored; actual playback starts after this position)
 * @param skipTime the skip time
 */
case class AudioSource(uri: String, index: Int, length: Long, skip: Long,
  skipTime: Long) {
  /**
   * Creates a new ''AudioSource'' object based on this instance with the
   * specified length.
   * @param newLength the length of the new source
   * @return the new ''AudioSource''
   */
  def resize(newLength: Long): AudioSource =
    AudioSource(uri, index, newLength, skip, skipTime)
}

/**
 * A message indicating that playback of a new audio source starts.
 * @param source the source which is now played
 */
case class PlaybackSourceStart(source: AudioSource)

/**
 * A message indicating that a source has been played completely.
 * @param source the source whose playback has finished
 * @param skipped a flag whether the source was skipped; a value of '''true'''
 * means that the source was not fully played; it has been interrupted
 */
case class PlaybackSourceEnd(source: AudioSource, skipped: Boolean)

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

/**
 * A message indicating that the playback time has changed. A message of this
 * type is sent after each ''PlaybackPositionChanged'' message provided that
 * the skip position is reached and there is a significant difference since the
 * last ''PlaybackTimeChanged'' message. Client applications can use this
 * message for instance to display the current playback time.
 * @param time the playback time (in milliseconds)
 */
case class PlaybackTimeChanged(time: Long)

/**
 * A message which is sent when information about a playlist has been updated.
 * Messages of this type are typically sent when new meta data about an audio
 * source (e.g. ID3 for a song) have become available. The index of the
 * corresponding audio source in the playlist is specified by the
 * ''updatedSourceDataIdx'' property. An event listener can update its display
 * so that the new information about this audio source is shown.
 * @param playlistData the object with all information about the current
 * playlist
 * @param updatedSourceDataIdx the index of the audio source which has been
 * updated; if the index is -1, the message was sent for other reasons
 */
case class PlaylistUpdate(playlistData: PlaylistData, updatedSourceDataIdx: Int)
