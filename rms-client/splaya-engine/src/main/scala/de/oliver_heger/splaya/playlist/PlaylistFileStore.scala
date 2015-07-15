package de.oliver_heger.splaya.playlist

/**
 * A trait for managing data files needed by a ''playlist manager''.
 *
 * A component managing a playlist typically has to persist some information so
 * that playback can be interrupted and later continued. Concrete
 * implementations of this trait provide this functionality. Data is passed to
 * and from an implementation in semi-structured form. Therefore, it is
 * extensible and can be stored in different ways (e.g. directly in the file
 * system or in a database).
 *
 * This trait offers the following functionality:
 * - A playlist can be identified using a unique identifier. This identifier
 * is calculated based on the playlist's content, i.e. the audio files to be
 * played.
 * - The status of the current playlist can be loaded and stored. This allows a
 * playlist manager to save the current position in the playlist.
 * - Meta data about a playlist - so-called ''playlist settings'' can be
 * queried. Here the playlist manager can obtain information about the name of
 * a playlist and a description plus an algorithm how to create a new playlist
 * if all songs have been played.
 */
trait PlaylistFileStore {
  /**
   * Returns a unique, alphanumeric ID for the specified playlist. The playlist
   * is determined by the audio files to be played. An implementation can
   * calculate a checksum or a hash value which should be unique for a specific
   * playlist.
   * @param playlist the playlist as a sequence of file names
   * @return the alphanumeric ID of the playlist
   */
  def calculatePlaylistID(playlist: Seq[String]): String

  /**
   * Loads the playlist information for the playlist with the given ID. Here
   * the current position in the playlist is stored. This information may not
   * be available.
   * @param playlistID the ID of the playlist in question
   * @return an ''Option'' with the playlist information as XML
   */
  def loadPlaylist(playlistID: String): Option[xml.Elem]

  /**
   * Saves information about the specified playlist.
   * @param playlistID the ID of the playlist in question
   * @param plElem the root XML element of the playlist
   */
  def savePlaylist(playlistID: String, plElem: xml.Elem)

  /**
   * Loads the settings for the specified playlist. The returned XML contains
   * meta data about this playlist. It may not be available.
   * @param playlistID the ID of the playlist in question
   * @return an ''Option'' with the playlist settings as XML
   */
  def loadSettings(playlistID: String): Option[xml.Elem]

  /**
   * Saves settings for the given playlist.
   * @param playlistID the ID of the playlist in question
   * @param root the root XML element of the playlist settings
   */
  def saveSettings(playlistID: String, root: xml.Elem)
}
