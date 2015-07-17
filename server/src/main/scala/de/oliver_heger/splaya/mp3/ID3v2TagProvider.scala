package de.oliver_heger.splaya.mp3

/**
 * An implementation of the ''ID3TagProvider'' trait based on an ID3v2 frame.
 *
 * This class is passed an array with tag names and an underlying ''ID3Frame''
 * object at construction time. The access methods for specific tags defined
 * by the ''ID3TagProvider'' trait are simply mapped to tag names in the array.
 *
 * Note: This class is used internally only, therefore it does not implement
 * sophisticated parameter checks.
 *
 * @param frame the underlying ID3v2 frame
 * @param tagNames the tag names corresponding to the specific access methods
 */
private class ID3v2TagProvider(val frame: ID3Frame, tagNames: Array[String])
  extends ID3TagProvider {
  def title: Option[String] = get(0)

  def artist: Option[String] = get(1)

  def album: Option[String] = get(2)

  def inceptionYearString: Option[String] = get(3)

  def trackNoString: Option[String] = get(4)

  /**
   * Obtains the value of the tag with the given index in the array of tag
   * names.
   * @param idx the index
   * @return an option with the value of this tag
   */
  private def get(idx: Int): Option[String] =
    frame.tags.get(tagNames(idx)) map (_.asString)
}
