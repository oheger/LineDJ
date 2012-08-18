package de.oliver_heger.splaya.fs

/**
 * Definition of a service interface providing access to a source medium with
 * audio files to be played.
 *
 * This interface provides methods for resolving URIs and for obtaining the
 * content of the source medium. It basically wraps a concrete file system
 * implementation.
 */
trait FSService {
  /**
   * Resolves the specified URI relative to the root URI. The root URI
   * identifies the medium, concrete files are relative to this URI. The object
   * returned by this method allows access to the represented audio stream.
   * @param root the root URI of the source medium
   * @param path the relative path to the file to be resolved
   * @return a data object for this URI
   * @throws IOException if an error occurs
   */
  def resolve(root: String, path: String): StreamSource

  /**
   * Scans the specified directory structure and returns a list with all found
   * media files. Media files are returned as URIs relative to the root URI of
   * the source medium. A set with the extensions of supported media files is
   * passed.
   * @param rootUri the URI pointing to the directory structure to be scanned
   * @param extensions a set with the file extensions of supported audio files
   * @return a list with the media files which have been discovered
   */
  def scan(rootUri: String, extensions: Set[String]): Seq[String]
}
