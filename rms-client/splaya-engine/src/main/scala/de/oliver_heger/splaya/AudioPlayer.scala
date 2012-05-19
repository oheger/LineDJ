package de.oliver_heger.splaya

/**
 * The basic interface of the audio player component.
 *
 * Through the methods defined by this interface playback of audio streams can
 * be controlled. There are methods for pausing and resuming playback, for
 * navigating through the playlist, and for registering listeners.
 */
trait AudioPlayer {
  /**
   * Starts playback if possible. If audio sources are available in the
   * playlist and all preconditions are met, playback starts with the current
   * song. If playback is already active, this method has no effect.
   */
  def startPlayback()

  /**
   * Pauses playback. It can be resumed later by calling ''startPlayback()''.
   * If playback is already stopped, this method has no effect.
   */
  def stopPlayback()

  /**
   * Moves forward to the next audio source in the playlist. The current audio
   * source is skipped.
   */
  def moveForward()

  /**
   * Moves backward in the current playlist. The exact behavior may depend on a
   * concrete implementation, but typically either the current source is played
   * again from start or the previous source in the playlist gets played -
   * depending on playback position in the current audio source.
   */
  def moveBackward()

  /**
   * Continues playback with the audio source at the specified index in the
   * playlist.
   * @param idx the index of the audio source to be played in the playlist
   */
  def moveToSource(idx: Int)

  /**
   * Reads the specified medium and creates a playlist with the audio sources
   * found. This method stops playback and clears the current playlist. It is
   * replaced by the newly created playlist. The passed in URI typically points
   * to a drive or the root directory with music files.
   * @param rootUri the URI of the medium to be read
   */
  def readMedium(rootUri: String)

  /**
   * Adds an ''AudioPlayerListener'' to this engine. It will get notified
   * when state changes of the audio engine occur.
   * @param listener the listener to be added
   */
  def addAudioPlayerListener(listener: AudioPlayerListener)

  /**
   * Removes the specified ''AudioPlayerListener'' from this engine.
   * @param listener the listener to be removed
   */
  def removeAudioPlayerListener(listener: AudioPlayerListener)

  /**
   * Adds the specified ''PlaylistListener'' to this engine. It will get
   * notified when a playlist is created or updated.
   * @param listener the listener to be added
   */
  def addPlaylistListener(listener: PlaylistListener)

  /**
   * Removes the specified ''PlaylistListener' from this engine.
   * @param listener the listener to be removed
   */
  def removePlaylistListener(listener: PlaylistListener)

  /**
   * Closes this audio player and frees all resources. This method should be
   * called at the end of an audio player application. Note that this method
   * - as is true for most methods defined by this trait - is asynchronous.
   * This means that the shutdown of the audio player engine is only
   * initiated, but not necessarily completed when this method returns. Clients
   * that need to know when the shutdown is complete should register themselves
   * as event listeners and wait for a player shutdown event.
   */
  def shutdown()
}
