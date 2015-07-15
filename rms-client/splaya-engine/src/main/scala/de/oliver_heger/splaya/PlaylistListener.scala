package de.oliver_heger.splaya

/**
 * A trait defining an event listener interface for objects interested in
 * notifications about playlist updates.
 *
 * The methods of this trait are called when a playlist was newly created or
 * updated. This mechanism is intended to be used by non-actor clients. It is
 * also suitable for Java clients.
 */
trait PlaylistListener {
  /**
   * A new playlist has been created.
   * @param ev the event object with all information available
   */
  def playlistCreated(ev: PlaylistEvent)

  /**
   * The current playlist has been updated.
   * @param ev the event object with all information available
   */
  def playlistUpdated(ev: PlaylistEvent)
}
