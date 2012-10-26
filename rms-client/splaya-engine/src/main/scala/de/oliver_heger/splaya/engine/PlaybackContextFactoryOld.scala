package de.oliver_heger.splaya.engine;
import java.io.InputStream

/**
 * <p>An interface for objects that can create a {@code PlaybackContext}.</p>
 * <p>Typical implementations of this trait know to setup audio streams and a
 * source line based on a stream with audio data to be played.</p>
 */
trait PlaybackContextFactoryOld {
  /**
   * Creates a context object for the specified input stream.
   * @param stream the input stream
   * @return the playback context
   */
  def createPlaybackContext(stream: InputStream): PlaybackContext
}
