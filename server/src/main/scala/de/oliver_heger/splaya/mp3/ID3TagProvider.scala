package de.oliver_heger.splaya.mp3

/**
 * Definition of a trait providing direct access to the most important ID3
 * tags.
 *
 * Through the interface defined here values for central ID3 tags can be
 * queried without having to respect a specific ID3 version. Because all tags
 * may be absent the methods return ''Option'' objects.
 */
trait ID3TagProvider {
  /**
   * Returns the title of the song.
   * @return an option for the song title
   */
  def title: Option[String]

  /**
   * Returns the name of the performing artist.
   * @return an option for the artist name
   */
  def artist: Option[String]

  /**
   * Returns the name of the album.
   * @return an option for the album name
   */
  def album: Option[String]

  /**
   * Returns a string value for the inception year.
   * @return an option for the inception year as string
   */
  def inceptionYearString: Option[String]

  /**
   * Returns a string value for the track number.
   * @return an option for the track number as string
   */
  def trackNoString: Option[String]

  /**
   * Returns the inception year.
   * @return an option for the inception year
   */
  def inceptionYear: Option[Int] =
    inceptionYearString flatMap parseNumericProperty

  /**
   * Returns the numeric track number.
   * @return an option for the track number
   */
  def trackNo: Option[Int] =
    trackNoString flatMap parseNumericProperty

  /**
   * Parses a (partly) numeric property in string form. If the property value
   * is not numeric, ''None'' is returned. Otherwise, the leading part of the
   * value that consists only of digits is parsed into a numeric value.
   *
   * @param value the value of the property as a string
   * @return an option for the corresponding numeric value
   */
  private def parseNumericProperty(value: String): Option[Int] = {
    val idx = value.indexWhere(!Character.isDigit(_))
    if (idx == 0) {
      None
    } else {
      val part = if (idx < 0) value else value.substring(0, idx)
      Some(part.toInt)
    }
  }
}
