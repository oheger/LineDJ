package de.oliver_heger.splaya.playlist
import de.oliver_heger.splaya.AudioSourceData

/**
 * A trait which defines an interface for obtaining meta data for an audio
 * source.
 *
 * An implementation of this trait is used to obtain the ID3 tags for the audio
 * files to be played. This meta data about audio files is exposed through the
 * [[de.oliver_heger.splaya.AudioSourceData]] trait. Because not for all audio
 * files meta data can be extracted an ''Option'' of this type is returned.
 */
trait AudioSourceDataExtractor {
  def extractAudioSourceData(uri: String): Option[AudioSourceData]
}
