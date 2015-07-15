package de.oliver_heger.splaya

/**
 * A trait providing information about a playlist.
 *
 * An object implementing this trait contains all information available about a
 * playlist and the audio sources (i.e. songs) it contains. Meta information
 * about the playlist as a whole is provided in terms of a
 * [[de.oliver_heger.splaya.PlaylistSettings]] object. For each audio source
 * in the playlist its URI and an object with properties (of type
 * [[de.oliver_heger.splaya.AudioSourceData]]) can be queried.
 */
trait PlaylistData {
  /**
   * Returns an object with meta information about the playlist as a whole.
   * @return the ''PlaylistSettings'' object
   */
  def settings: PlaylistSettings

  /**
   * Returns the size of this playlist. This is the number of audio sources
   * contained in the playlist.
   * @return the size of the playlist
   */
  def size: Int

  /**
   * Returns the index of the first audio source of this playlist. For a newly
   * created playlist this index is always 0. When a persistent playlist is to
   * be continued, the current index is provided here.
   * @return the start index in this playlist
   */
  def startIndex: Int

  /**
   * Returns the URI of the audio source at the specified index.
   * @param idx the index (0-based)
   * @return the URI of the audio source at this index
   */
  def getURI(idx: Int): String

  /**
   * Returns an object with data about the audio source at the specified index.
   * An implementation must never return '''null'''. If no data about an audio
   * source is available, an object with default data has to be returned
   * @param idx the index (0-based)
   * @return a ''AudioSourceData'' object for the specified source
   */
  def getAudioSourceData(idx: Int): AudioSourceData
}
