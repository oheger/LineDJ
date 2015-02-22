package de.oliver_heger.splaya.playback

/**
 * A data class describing an audio source to be played by the audio player
 * engine. This class contains some meta data about the source and its position
 * in the current playlist.
 *
 * @param uri the URI of the source
 * @param index the index of this source in the current playlist
 * @param length the length of the source (in bytes)
 * @param skip the skip position (i.e. the part of the stream at the beginning
 *             which is to be ignored; actual playback starts after this position)
 * @param skipTime the skip time
 */
case class AudioSource(uri: String, index: Int, length: Long, skip: Long,
                       skipTime: Long)
