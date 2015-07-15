package de.oliver_heger.splaya.playback

import java.io.InputStream

/**
 * A trait for objects that know how to create a [[PlaybackContext]].
 *
 * This factory is used to allow audio playback for different audio formats.
 * A concrete implementation gets passed the current audio stream and the URI
 * of the audio file. It can decide whether this file is supported or not. If
 * it is supported, a corresponding playback object is created and returned.
 * Otherwise, result is ''None''. The passed in URI of the current audio file
 * can be used as an indicator whether the file is supported or not, e.g. by
 * inspecting the file extension.
 *
 * A concrete implementation should not throw an exception. In case of an
 * unsupported format (even for files that should be supported, but contain
 * unexpected data), a ''None'' object should be returned. This allows for a
 * fallback mechanism giving other factory implementations available a chance
 * to check the audio source.
 */
trait PlaybackContextFactory {
  /**
   * Creates a suitable ''PlaybackContext'' object for the specified audio
   * stream if the format is supported. Otherwise, returns ''None''.
   * @param stream the stream with the current audio data
   * @param uri the URI pointing to the file to be played
   * @return an optional ''PlaybackContext'' for this audio source
   */
  def createPlaybackContext(stream: InputStream, uri: String): Option[PlaybackContext]
}
