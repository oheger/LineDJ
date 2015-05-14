package de.oliver_heger.splaya.playlist.gen.randomdir

import de.oliver_heger.splaya.playlist.PlaylistGeneratorRandomUriPart

/**
 * A specialized ''PlaylistGenerator'' which produces a playlist in random
 * order of the single albums.
 *
 * With this generator the albums contained on a medium can be listened to in
 * random order, but the songs of a single album are ordered. In order to
 * determine albums, the passed in song URIs are split at the path delimiter
 * character '/'. The album component is considered to be one before the last
 * component (the last one being the file name). URIs that consist only of a
 * single component (i.e. a file name) are considered to belong all to the same
 * synthetic album.
 */
class PlaylistGeneratorRandomDir extends PlaylistGeneratorRandomUriPart {
  /**
   * @inheritdoc This implementation returns the album part of the song URI,
   *             which is the part before the last one.
   */
  override protected def extractUriPart(parts: Array[String]): String =
    parts(parts.length - 2)
}
