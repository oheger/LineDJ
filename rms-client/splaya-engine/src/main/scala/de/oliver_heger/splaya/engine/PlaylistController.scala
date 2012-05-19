package de.oliver_heger.splaya.engine
import scala.actors.Actor

/**
 * Definition of an interface for a component which is responsible for
 * managing the audio player's playlist.
 *
 * An implementation of this interface must be able to find audio files on a
 * source medium and to order them in a playlist. Then this playlist has to be
 * communicated to other parts of the audio player engine. How this is done is
 * not specified by this interface. It merely contains the methods needed by
 * the audio player implementation to interact with the playlist manager.
 *
 * There are methods for navigating through the playlist. They are usually
 * called as reaction on user actions. When such a method is called the engine
 * has cleared its current playlist. So an implementation has to send the
 * whole playlist - starting from the current position - to the engine.
 */
trait PlaylistController {
  /**
   * Moves to the source at the specified index in the playlist. The content of
   * the playlist (from this position on) must be sent again to the audio player
   * engine.
   * @param idx the new position in the playlist
   */
  def moveToSourceAt(idx: Int)

  /**
   * Moves to a relative position in the current playlist. The specified delta
   * is added to the current playlist position. After that - as is true for
   * ''moveToSourceAt()'' - the content of the playlist (for the new position
   * on) must be sent again to the audio player engine. The delta may be 0,
   * in which case the current audio source should be played again. It is also
   * possible that the delta causes the index in the playlist to become
   * invalid; in this case an implementation should gracefully correct it
   * appropriately. For instance, if the new current index is -1, it should be
   * set to 0.
   * @param delta the delta to be added to the current playlist position; it can
   * be positive or negative
   */
  def moveToSourceRelative(delta: Int)

  /**
   * Searches for all audio sources on the specified medium and arranges them
   * in a new playlist. Then the playlist has to be communicated to the audio
   * player engine.
   * @param rootUri the URI of the medium to be read
   */
  def readMedium(rootUri: String)

  /**
   * Performs a shutdown of this ''PlaylistController''. An implementation
   * should release all resources in use. Maybe the current state of the
   * playlist can be persisted so that playback can be resumed at the very same
   * position. (But this is not specified by this interface.) Note that this
   * method is asynchronous, i.e. it just triggers the shutdown of the
   * playlist controller, but does not wait until it is complete.
   */
  def shutdown()
}
