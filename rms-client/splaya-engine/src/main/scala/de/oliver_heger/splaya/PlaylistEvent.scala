package de.oliver_heger.splaya

/**
 * A trait defining events sent by the audio player engine that are related to
 * the current playlist.
 *
 * Events of this type are processed by
 * [[de.oliver_heger.splaya.PlaylistListener]] implementations. They can be
 * used for instance to keep track about the current playlist and meta
 * information about the single items.
 */
trait PlaylistEvent {
  /**
   * Returns the type of this event.
   * @return the event type
   */
  def getType: PlaylistEventType

  /**
   * Returns the data object describing the playlist. This immutable data object
   * can be used to query all items in the playlist and their associated meta
   * data.
   * @return the ''PlaylistData'' object for the current playlist
   */
  def getPlaylistData: PlaylistData

  /**
   * Returns the index of the item in the current playlist which has been
   * updated. This property is defined only for playlist update events.
   * @return the index of the updated playlist item
   */
  def getUpdateIndex: Int
}
