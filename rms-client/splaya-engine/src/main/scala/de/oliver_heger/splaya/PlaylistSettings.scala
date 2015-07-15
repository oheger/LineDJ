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

  /**
   * Returns additional parameters as a structured XML node sequence.
   * Depending in the concrete order mode, more information may be required to
   * produce an ordered playlist. This can be provided here in an extensible
   * form.
   * @return additional parameters for ordering the playlist
   */
  def orderParams: xml.NodeSeq

  /**
   * Returns the root URI of the medium from which the associated playlist has
   * been constructed. This information is typically not persisted, it is set
   * dynamically when the source medium is read and a playlist is constructed.
   * It merely serves informational purpose.
   * @return the root URI of the source medium from which the playlist was read
   */
  def mediumURI: String
}
