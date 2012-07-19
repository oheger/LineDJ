package de.oliver_heger.splaya.fs
import java.io.InputStream

/**
 * A trait for a simple data object allowing access to an audio stream.
 *
 * Objects implementing this trait are returned by ''FSService''. They
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
  def openStream(): InputStream
}
