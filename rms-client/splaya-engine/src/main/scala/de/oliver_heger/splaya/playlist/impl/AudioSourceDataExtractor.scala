package de.oliver_heger.splaya.playlist.impl
import de.oliver_heger.splaya.AudioSourceData
import de.oliver_heger.splaya.MediaDataExtractor

/**
 * A trait which defines an interface for obtaining meta data for an audio
 * source.
 *
 * An implementation of this trait is used to obtain meta data for the audio
 * files to be played. This meta data about audio files is exposed through the
 * [[de.oliver_heger.splaya.AudioSourceData]] trait. Because not for all audio
 * files meta data can be extracted an ''Option'' of this type is returned.
 *
 * The actual extraction of meta data is done by objects implementing the
 * [[de.oliver_heger.splaya.playlist.MediaDataExtractor]] trait. An
 * implementation manages a number of ''MediaDataExtractor''s and delegates
 * to them when doing an extraction. The media extractors available in the
 * system can be added and removed dynamically.
 */
trait AudioSourceDataExtractor {
  /**
   * Tries to extract audio data for the specified audio file.
   * @param mediumURI the URI of the source medium
   * @param uri the relative URI of the audio file in question
   * @return an ''Option'' object with extracted audio data
   */
  def extractAudioSourceData(mediumURI: String, uri: String): Option[AudioSourceData]

  /**
   * Adds a ''MediaDataExtractor'' to this object. By calling this method with
   * different extractors, support for different audio file formats can be
   * realized.
   * @param extr the extractor to be added
   */
  def addMediaDataExtractor(extr: MediaDataExtractor): Unit

  /**
   * Removes the given ''MediaDataExtractor'' from this object.
   * @param extr the extractor to be removed
   */
  def removeMediaDataExtractor(extr: MediaDataExtractor): Unit
}
