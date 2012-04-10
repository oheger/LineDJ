package de.oliver_heger.splaya.playlist.impl
import de.oliver_heger.splaya.PlaylistData
import de.oliver_heger.splaya.PlaylistSettings
import de.oliver_heger.splaya.AudioSourceData

/**
 * A default implementation of the ''PlaylistData'' trait.
 *
 * Objects of this class are created and published through the event
 * notification mechanism when a new playlist has been constructed. Client code
 * can use the data available through the ''PlaylistData'' trait to display
 * information about the current playlist to the end user.
 *
 * This information is more or less straight-forward. Many properties are
 * directly passed to the constructor. Instances are immutable and thus can be
 * shared between multiple threads.
 *
 * @param settings the data object for playlist settings
 * @param startIndex the start index of this playlist
 * @param playlist a sequence with the URIs of the audio sources in the playlist
 * @param sourceDataOrg an array with the available ''AudioSourceData'' objects
 * for the sources in the playlist (a defensive copy will be created)
 */
private case class PlaylistDataImpl(settings: PlaylistSettings, startIndex: Int,
  playlist: Seq[String], private val sourceDataOrg: Array[AudioSourceData])
  extends PlaylistData {
  /** An array for direct access to playlist URIs. */
  private var playlistArray = playlist.toArray

  /** A defensive copy of the array with source data objects. */
  private var sourceData = sourceDataOrg.clone()

  val size = playlist.size

  def getURI(idx: Int) = playlistArray(idx)

  /**
   * @inheritdoc This implementation checks whether an ''AudioSourceData''
   * object is available for the item with the given index. If so, it is
   * returned. Otherwise, a dummy object is created with only the ''title''
   * property set.
   */
  def getAudioSourceData(idx: Int): AudioSourceData =
    if (sourceData(idx) != null) sourceData(idx) else createDummySourceData(idx)

  /**
   * Creates an ''AudioSourceData'' object for a playlist item for which no
   * meta data is available. This is a dummy data object with only a subset of
   * properties defined. The title is derived from the URI, other properties
   * have dummy values.
   * @param idx the index of the playlist item
   * @return the data object for this playlist item
   */
  private def createDummySourceData(idx: Int): AudioSourceData =
    AudioSourceDataImpl(title = getURI(idx), albumName = null,
      artistName = null, duration = 0, trackNo = 0, inceptionYear = 0)
}
