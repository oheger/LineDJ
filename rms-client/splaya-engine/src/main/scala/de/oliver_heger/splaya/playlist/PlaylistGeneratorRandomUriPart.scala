package de.oliver_heger.splaya.playlist

import scala.xml.NodeSeq

object PlaylistGeneratorRandomUriPart {
  /** Constant for an undefined URI part. */
  val UndefinedPart = ""

  /** The separator for path components. */
  private val Separator = "/"
}

/**
 * A special base implementation for concrete ''PlaylistGenerator'' classes
 * that produce a random order based specific parts of the song URI.
 *
 * Concrete example use cases are a random album playlist generator or a random
 * artist playlist generator. In the former case, songs are sorted by their
 * last directory component; in the latter case, sorting is done by the first
 * directory. This assumes a typical medium structure in which the top level
 * directory contains sub folders for the single artists. Each artist folder
 * then has further sub folders for the different albums.
 *
 * This trait implements basic functionality for producing partly ordered lists
 * played in random order. Concrete implementations mainly have to extract the
 * part from the song URI which is used as random component.
 *
 * Some media may contain songs directly on the top level directory. Such songs
 * cannot be assigned to an album or an artist. A concrete implementation has
 * to decide how to treat such elements when generating the random playlist.
 */
trait PlaylistGeneratorRandomUriPart extends PlaylistGenerator {

  import PlaylistGeneratorRandomUriPart._

  /**
   * @inheritdoc This implementation generates a playlist in random order. It
   *             delegates to the ''extractUriPart()'' method for letting a
   *             concrete sub class extract the interesting URI part. If a
   *             song is to be processed that consists of only a a single part
   *             (i.e. a song in the top level directory), the method
   *             ''extractSinglePart()'' is invoked.
   */
  override def generatePlaylist(songs: Seq[String], mode: String, params: NodeSeq): Seq[String] = {
    val albumList = songs map extractPartFromSongUri
    val songAlbumMapping = Map(songs zip albumList: _*)
    val distinctAlbums = albumList.toSet.toList
    val albumIndices = util.Random.shuffle(distinctAlbums.indices)
    val albumIndexMapping = Map(distinctAlbums zip albumIndices: _*)

    def albumIndex(s: String): Int = albumIndexMapping(songAlbumMapping(s))

    songs.sortWith { (song1, song2) =>
      val idx1 = albumIndex(song1)
      val idx2 = albumIndex(song2)
      if (idx1 != idx2) idx1 < idx2
      else song1.compareTo(song2) < 0
    }
  }

  /**
   * Extracts the part of the song URI which is relevant for generating the
   * random order. This method is called for each song after it has been split
   * into the several URI parts using the delimiter '/'. This method is only
   * called if the song had at least two URI parts; otherwise, the method
   * ''extractSinglePart()'' is invoked.
   * @param parts an array with the parts the song URI consists of
   * @return the part to be used for generating the random playlist
   */
  protected def extractUriPart(parts: Array[String]): String

  /**
   * Determines the URI part to work with if the song URI consists only of the
   * name. It depends on a concrete implementation how to proceed in this
   * case. This base implementation returns an undefined part. This has the
   * consequence that this song is added to a synthetic song group.
   * @param part the single part of the song name
   * @return the string mapped to this part
   */
  protected def extractSinglePart(part: String): String = UndefinedPart

  /**
   * Extracts the relevant URI part from the given song URI.
   * @param song the song URI
   * @return the URI part to work with
   */
  private def extractPartFromSongUri(song: String): String = {
    val components = song split Separator
    if (components.length < 2) extractSinglePart(components.head)
    else extractUriPart(components)
  }
}
