package de.oliver_heger.splaya.playlist

/**
 * Definition of an interface for a component which scans a directory structure
 * for media files that can be played by the audio player.
 *
 * The interface is pretty simple: There is a single ''scan()'' method which is
 * parsed a URI representing the root of the directory structure to be scanned.
 * It returns a list with the URIs of the media files (as strings) that have
 * been detected.
 */
trait FSScanner {
  /**
   * Scans the specified directory structure and returns a list with all found
   * media files.
   * @param rootUri the URI pointing to the directory structure to be scanned
   * @return a list with the media files which have been discovered
   */
  def scan(rootUri: String): Seq[String]
}
