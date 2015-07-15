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
 * This implementation is more or less straight-forward. Many properties are
 * directly passed to the constructor. However, ''AudioSourceData'' objects are
 * set when they become available. So this class is not immutable. This should
 * not be a problem on client side because updated instances are sent as
 * messages which means that they are safely published. (Note: It turned out
 * that immutable objects are inefficient for large playlists.)
 *
 * @param settings the data object for playlist settings
 * @param startIndex the start index of this playlist
 * @param playlist a sequence with the URIs of the audio sources in the playlist
 */
private case class PlaylistDataImpl(settings: PlaylistSettings, startIndex: Int,
  playlist: Seq[String]) extends PlaylistData {
  /** An array for direct access to playlist URIs. */
  private var playlistArray = playlist.toArray

  val size = playlist.size

  /** The array with source data objects. */
  private var sourceData = new Array[AudioSourceData](size)

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
   * Sets the ''AudioSourceData'' object for the playlist item with the given
   * index.
   * @param idx the index of the playlist item
   * @param data the data object to be set
   */
  def setAudioSourceData(idx: Int, data: AudioSourceData) {
    sourceData(idx) = data
  }

  /**
   * Creates an ''AudioSourceData'' object for a playlist item for which no
   * meta data is available. This is a dummy data object with only a subset of
   * properties defined. The title is derived from the URI, other properties
   * have dummy values.
   * @param idx the index of the playlist item
   * @return the data object for this playlist item
   */
  private def createDummySourceData(idx: Int): AudioSourceData =
    AudioSourceDataImpl(title = extractAudioSourceName(idx), albumName = null,
      artistName = null, duration = 0, trackNo = 0, inceptionYear = 0)

  /**
   * Extracts the name of an audio source from its URI. Path elements and the
   * file extension are stripped.
   * @param idx the index of the playlist item
   * @return the extracted audio source name for this item
   */
  private def extractAudioSourceName(idx: Int): String = {
    val uri = getURI(idx)
    val posPrefix = scala.math.max(uri lastIndexOf ':', uri lastIndexOf '/')
    var posExt = uri lastIndexOf '.'
    if (posExt >= 0 && posExt < posPrefix) {
      posExt = -1
    }
    val uriNoExt = if (posExt < 0) uri else uri take posExt
    if (posPrefix < 0) uriNoExt else uriNoExt drop posPrefix + 1
  }
}
