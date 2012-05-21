package de.oliver_heger.splaya.engine

import de.oliver_heger.splaya.AudioPlayer
import scala.actors.Actor
import de.oliver_heger.splaya.engine.msg.Gateway
import de.oliver_heger.splaya.engine.msg.StartPlayback
import de.oliver_heger.splaya.engine.msg.StopPlayback
import de.oliver_heger.splaya.engine.msg.SkipCurrentSource
import de.oliver_heger.splaya.engine.msg.TimeAction
import de.oliver_heger.splaya.engine.msg.Exit
import de.oliver_heger.splaya.engine.msg.FlushPlayer
import de.oliver_heger.splaya.PlaylistListener
import de.oliver_heger.splaya.AudioPlayerListener
import msg.AddAudioPlayerEventListener
import de.oliver_heger.splaya.engine.msg.RemoveAudioPlayerEventListener
import msg.AddPlaylistEventListener
import de.oliver_heger.splaya.engine.msg.RemovePlaylistEventListener

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
 * @param timingActor the actor responsible for timing
 * @param eventActor the actor responsible for event translation
 * @param moveBackwardThreshold a time (in milliseconds) which controls the
 * behavior of the ''moveBackward()'' operation: this method checks the current
 * playback time; if it is less than this value, it moves back in the playlist
 * to the previous audio source; otherwise, the current audio source is played
 * again
 */
class AudioPlayerImpl(val playlistController: PlaylistController,
  timingActor: Actor, eventActor: Actor, val moveBackwardThreshold: Long = 5000)
  extends AudioPlayer {
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

  /**
   * @inheritdoc This implementation interacts with the ''TimingActor'' to
   * obtain the current playback time. If it is below the
   * ''moveBackwardThreshold'' value, it moves to the previous audio source in
   * the playlist. Otherwise, it causes the current audio source to be played
   * again. In any case the playlist is updated, therefore a flush operation
   * has to be performed.
   */
  def moveBackward() {
    flush()
    timingActor ! TimeAction { time =>
      playlistController.moveToSourceRelative(
        if (time < moveBackwardThreshold) -1
        else 0)
    }
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
   * @inheritdoc This implementation delegates to the event translation actor.
   */
  def addAudioPlayerListener(listener: AudioPlayerListener) {
    eventActor ! AddAudioPlayerEventListener(listener)
  }

  /**
   * @inheritdoc This implementation delegates to the event translation actor.
   */
  def removeAudioPlayerListener(listener: AudioPlayerListener) {
    eventActor ! RemoveAudioPlayerEventListener(listener)
  }

  /**
   * @inheritdoc This implementation delegates to the event translation actor.
   */
  def addPlaylistListener(listener: PlaylistListener) {
    eventActor ! AddPlaylistEventListener(listener)
  }

  /**
   * @inheritdoc This implementation delegates to the event translation actor.
   */
  def removePlaylistListener(listener: PlaylistListener) {
    eventActor ! RemovePlaylistEventListener(listener)
  }

  /**
   * @inheritdoc This implementation registers the specified actor at the
   * Gateway.
   */
  def addListenerActor(actor: Actor) {
    Gateway.register(actor)
  }

  /**
   * @inheritdoc This implementation removes the specified actor from the
   * Gateway.
   */
  def removeListenerActor(actor: Actor) {
    Gateway.unregister(actor)
  }

  /**
   * @inheritdoc This implementation sends exit messages to the actors
   * comprising the audio engine. Also, the ''PlaylistController'' is told to
   * shutdown.
   */
  def shutdown() {
    shutdownActors()
    playlistController.shutdown()
  }

  /**
   * Flushes the actors of the audio engine. This causes the temporary buffer
   * with streamed audio data to be invalidated.
   */
  private def flush() {
    Gateway ! Gateway.ActorSourceRead -> FlushPlayer
  }

  /**
   * Sends all actors involved an ''Exit'' message.
   * @return the ''Exit'' message
   */
  private def shutdownActors() {
    val ex = Exit()
    Gateway ! Gateway.ActorSourceRead -> ex
    Gateway ! Gateway.ActorPlayback -> ex
    Gateway ! Gateway.ActorLineWrite -> ex
    timingActor ! ex
  }
}
