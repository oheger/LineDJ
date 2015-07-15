package de.oliver_heger.splaya

/**
 * A trait describing meta data about an audio source.
 *
 * This interface is used for accessing additional information about an audio
 * source. In case of MP3 files for instance, this information is extracted
 * from ID3 tags. An audio player UI typically displays this information
 * rather than the pure URI to the audio file.
 *
 * Note that it is not unusual that properties are undefined. In this case,
 * string properties are '''null''' while numeric properties are 0. A client
 * application should be aware of this.
 */
trait AudioSourceData {
  /**
   * Returns the title of this audio source. This is the song name.
   * @return the title
   */
  def title: String

  /**
   * Returns the name of the album this song belongs to.
   * @return the album name
   */
  def albumName: String

  /**
   * Returns the name of the artist who performed this song.
   * @return the artist name
   */
  def artistName: String

  /**
   * Returns the duration of this song in milliseconds.
   * @return the duration in milliseconds
   */
  def duration: Long

  /**
   * Returns the inception year of this song.
   * @return the inception year
   */
  def inceptionYear: Int

  /**
   * Returns the track number of this song.
   * @return the track number
   */
  def trackNo: Int
}
