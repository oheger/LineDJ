package de.oliver_heger.splaya.playlist.gen.randomart

import de.oliver_heger.splaya.playlist.PlaylistGeneratorRandomUriPart

/**
 * A specialized ''PlaylistGenerator'' which produces a playlist in random
 * order of the single artists contained on a medium.
 *
 * The albums of an artist and the songs contained on them are played in the
 * order defined by their names. However, the order in which artists are
 * played is random.
 *
 * Song URIs that do not contain an artist name (these are song files in the
 * top level directory of a medium) are handled in a special way: They are
 * considered to have different artists; so they are also played in random
 * order.
 */
class PlaylistGeneratorRandomArtist extends PlaylistGeneratorRandomUriPart {
  /**
   * @inheritdoc This implementation returns the first part which is the part
   *             for the artist.
   */
  override protected def extractUriPart(parts: Array[String]): String = parts(0)

  /**
   * @inheritdoc This implementation returns the passed in part. This means
   *             that songs without an artist in their name are treated as if
   *             they belong to a unique artist; thus they will appear in
   *             random order.
   */
  override protected def extractSinglePart(part: String): String = part
}
