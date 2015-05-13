package de.oliver_heger.splaya.playlist.gen.randomdir

import de.oliver_heger.splaya.playlist.PlaylistGenerator

import scala.xml.NodeSeq

object PlaylistGeneratorRandomDir {
  /** The separator for path components. */
  private val Separator = "/"

  /** Constant for an undefined album. */
  private val UndefinedAlbum = ""

  /**
   * Extracts the album name from the given song URI.
   * @param song the song URI
   * @return the album name
   */
  private def extractAlbum(song: String): String = {
    val components = song split Separator
    if (components.length < 2) UndefinedAlbum
    else components(components.length - 2)
  }
}

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
class PlaylistGeneratorRandomDir extends PlaylistGenerator {

  import PlaylistGeneratorRandomDir._

  /**
   * Generates a playlist based on the given mode and the list of songs on the
   * current medium.
   * @param songs a list with the URIs of audio sources (i.e. songs) found on
   *              the current medium; an ordered list of these songs is referred to as
   *              playlist
   * @param mode the order mode; the concrete value of this string has to be
   *             interpreted by a specific implementation
   * @param params additional parameters as XML
   * @return the ordered playlist
   */
  override def generatePlaylist(songs: Seq[String], mode: String, params: NodeSeq): Seq[String] = {
    val albumList = songs map extractAlbum
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
}
