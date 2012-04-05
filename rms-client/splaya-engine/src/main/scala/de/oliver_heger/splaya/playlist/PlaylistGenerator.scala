package de.oliver_heger.splaya.playlist
import scala.xml.Elem

/**
 * Definition of an interface for a component which can generate a playlist.
 *
 * The audio player engine supports a hook for constructing a playlist using
 * different algorithms. This interface has to be implemented by playlist
 * generator extensions.
 *
 * The basic idea is that there is a mode string describing the way the playlist
 * is to be generated (e.g. ordered by directories, random order, specific
 * order, etc.). Depending on the mode, there can be additional arguments
 * which are specified as semi-structured XML data.
 */
trait PlaylistGenerator {
  /**
   * Generates a playlist based on the given mode and the list of songs on the
   * current medium.
   * @param songs a list with the URIs of audio sources (i.e. songs) found on
   * the current medium; an ordered list of these songs is referred to as
   * playlist
   * @param mode the order mode; the concrete value of this string has to be
   * interpreted by a specific implementation
   * @param params additional parameters as XML
   * @return the ordered playlist
   */
  def generatePlaylist(songs: List[String], mode: String, params: Elem): List[String]
}
