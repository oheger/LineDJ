package de.oliver_heger.splaya.engine

import de.oliver_heger.splaya.AudioPlayer

/**
 * The default implementation of the ''AudioPlayer'' trait.
 *
 * This implementation controls communication between a couple of actors and
 * the passed in ''PlaylistController'' object in order to provide basic audio
 * player services. The actors expect to be fed with the audio sources they
 * have to play. Therefore, the ''PlaylistController'' must have been
 * initialized correctly to pass the current playlist to the right actor. This
 * class mainly interacts with the [[de.oliver_heger.splaya.engine.PlaybackActor]]
 * actor; it is send messages to pause or resume playback in reaction of
 * corresponding method invocations. The
 * [[de.oliver_heger.splaya.engine.SourceReaderActor]] actor is expected to be
 * passed the current playlist. When the audio player is told to move to another
 * position in the playlist it is sent a flush message.
 *
 * From client code, a typical usage scenario of this class is as follows:
 * - ''readMedium()'' is called to read the content of the source medium. This
 * starts playback automatically.
 * - Depending on user actions, the methods for pausing and resuming, or for
 * navigating in the playlist can be called.
 * - ''readMedium()'' can be called again to construct a new playlist (from the
 * same or a different medium).
 * - Before the application exists, ''shutdown()'' should be called.
 *
 * @param playlistController the object managing playlist information
 */
class AudioPlayerImpl(val playlistController: PlaylistController) extends AudioPlayer {
  /**
   * @inheritdoc This implementation sends a ''StartPlayback'' message to the
   * playback actor.
   */
  def startPlayback() {
    Gateway ! Gateway.ActorPlayback -> StartPlayback
  }

  /**
   * @inheritdoc This implementation sends a '#StopPlayback'' message to the
   * playback actor.
   */
  def stopPlayback() {
    Gateway ! Gateway.ActorPlayback -> StopPlayback
  }

  /**
   * @inheritdoc Moving forward means skipping the current source. Therefore, a
   * corresponding skip message is sent to the playback actor.
   */
  def moveForward() {
    Gateway ! Gateway.ActorPlayback -> SkipCurrentSource
  }

  def moveBackward() {
    //TODO implementation
    throw new UnsupportedOperationException("Not yet implemented!");
  }

  /**
   * @inheritdoc When moving to an arbitrary source in the playlist a flush
   * operation has to be performed because the audio data streamed so far into
   * the temporary buffer is no more valid. Then the ''PlaylistController''
   * has to be moved to the desired index. It will then communicate the new
   * playlist to the audio engine.
   */
  def moveToSource(idx: Int) {
    flush()
    playlistController.moveToSourceAt(idx)
  }

  /**
   * @inheritdoc Reading a medium and constructing a corresponding playlist
   * causes the old playlist to be invalidated. Therefore, the audio engine has
   * to be flushed. After that the ''PlaylistController'' can be told to read
   * the specified medium.
   */
  def readMedium(rootUri: String) {
    flush()
    playlistController.readMedium(rootUri)
  }

  /**
   * @inheritdoc This implementation sends exit messages to the actors
   * comprising the audio engine. Also, the ''PlaylistController'' is told to
   * shutdown.
   */
  def shutdown() {
    Gateway ! Gateway.ActorSourceRead -> Exit
    Gateway ! Gateway.ActorPlayback -> Exit
    Gateway ! Gateway.ActorLineWrite -> Exit
    playlistController.shutdown()
  }

  /**
   * Flushes the actors of the audio engine. This causes the temporary buffer
   * with streamed audio data to be invalidated.
   */
  private def flush() {
    Gateway ! Gateway.ActorSourceRead -> FlushPlayer
  }
}
