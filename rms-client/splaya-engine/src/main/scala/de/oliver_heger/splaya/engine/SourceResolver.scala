package de.oliver_heger.splaya.engine
import java.io.InputStream

/**
 * A trait for objects which can resolve a URI of an audio source.
 *
 * A concrete implementation is passed in a URI as String. It returns a simple
 * data object with the length of this source and a method for opening an
 * input stream.
 */
trait SourceResolver {
  /**
   * Resolves the specified URI. The object returned by this method allows
   * access to the represented audio stream.
   * @param uri the URI to be resolved
   * @return a data object for this URI
   * @throws IOException if an error occurs
   */
  def resolve(uri: String): StreamSource
}

/**
 * A trait for a simple data object allowing access to an audio stream.
 *
 * Objects implementing this trait are returned by ''SourceResolver''. They
 * can be queried for the length of the stream and an input stream.
 */
trait StreamSource {
  /**
   * The size of this stream in bytes.
   */
  val size: Long

  /**
   * Opens an input stream to the underlying audio source.
   * @return the input stream
   * @throws IOException if an error occurs
   */
  def openStream: InputStream
}