package de.oliver_heger.splaya

/**
 * A trait defining the settings of a playlist.
 *
 * For each playlist some meta data can be stored, including a playlist name
 * and a description. The methods defined here can be used to access the
 * properties.
 */
trait PlaylistSettings {
  /**
   * Returns the name of the playlist.
   * @return the playlist name
   */
  def name: String

  /**
   * Returns the description of the playlist.
   * @return the playlist description
   */
  def description: String

  /**
   * Returns a string representing the ''order mode'' of the playlist. This
   * string determines the order of the single audio sources when a new playlist
   * is to be constructed.
   * @return the playlist's order mode
   */
  def orderMode: String
}
