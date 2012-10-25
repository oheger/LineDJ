package de.oliver_heger.splaya;
import java.io.InputStream

/**
 * An interface for objects that can create a
 * [[de.oliver_heger.splaya.PlaybackContext]].
 *
 * A playback context contains all information required for playing an audio
 * file. The responsibility of an implementation of this trait is to transform
 * an input stream into an audio stream so that playback can be performed.
 * A typical implementation will handle a specific audio format. If the
 * input stream is supported, a corresponding context object is set up.
 * Otherwise, result must be ''None'', and the input stream must not be
 * modified. The passed in [[de.oliver_heger.splaya.AudioSource]] object can
 * be queried to determine whether the stream is supported (e.g. by
 * inspecting the file extension).
 */
trait PlaybackContextFactory {
  /**
   * Creates a context object for the specified input stream.
   * @param stream the input stream
   * @param source the underlying audio source object
   * @return an option for the playback context
   */
  def createPlaybackContext(stream: InputStream, source: AudioSource): Option[PlaybackContext]
}
