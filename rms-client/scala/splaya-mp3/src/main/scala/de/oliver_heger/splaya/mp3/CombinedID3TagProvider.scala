package de.oliver_heger.splaya.mp3

/**
 * An implementation of the ''ID3TagProvider'' trait which manages a number of
 * ''ID3TagProvider'' objects.
 *
 * An mp3 audio file can contain multiple ID3 frames of different versions.
 * The content of these frames may differ. For instance, it could be possible
 * that a tag is defined in a ID3v1 frame, but not in an ID3v2 frame. To deal
 * with such constellations, an instance of this class can be constructed with
 * a list of concrete ''ID3TagProcider'' objects. When asked for a specific
 * tag, the provider iterates over this list until it finds a value or the
 * end of the list is reached (in the latter case result is ''None'').
 *
 * Note that the sub providers are queried in exact the order they are contained
 * in the list passed to the constructor. So there is an implicit priorization:
 * providers at the head of the list take precedence over providers at the
 * tail.
 *
 * @param subProviders a sequence with the managed sub providers
 */
class CombinedID3TagProvider(val subProviders: List[ID3TagProvider])
  extends ID3TagProvider {

  def title: Option[String] = getTag(_.title)

  def artist: Option[String] = getTag(_.artist)

  def album: Option[String] = getTag(_.album)

  def inceptionYearString: Option[String] = getTag(_.inceptionYearString)

  def trackNoString: Option[String] = getTag(_.trackNoString)

  /**
   * Helper method for obtaining a specific tag value. This method iterates
   * over all sub providers and applies the given function until either the
   * end of list of sub providers is reached or a value is found.
   * @param f the function for retrieving a value from a tag provider
   * @return the found value
   */
  private def getTag(f: ID3TagProvider => Option[String]): Option[String] =
    findTagValue(subProviders, f)

  /**
   * Recursively iterates over the list of sub providers and applies the given
   * function until a value is found or there are no more sub providers.
   * @param providers the current sequence of sub providers
   * @param f the function for retrieving a value from a tag provider
   * @return the found value
   */
  private def findTagValue(providers: Seq[ID3TagProvider],
    f: ID3TagProvider => Option[String]): Option[String] = {
    providers match {
      case List() => None
      case p :: pseq =>
        val value = f(p)
        if (value.isDefined) value
        else findTagValue(pseq, f)
    }
  }
}
