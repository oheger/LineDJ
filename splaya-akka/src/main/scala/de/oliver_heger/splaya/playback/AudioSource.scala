package de.oliver_heger.splaya.playback

import akka.actor.ActorRef

/**
 * A data class describing an audio source to be played by the audio player
 * engine. This class contains some meta data about the source and its position
 * in the current playlist. It is mainly used internally by the engine.
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

/**
 * A data class which uniquely identifies an audio source.
 *
 * This message class is used to obtain information about a specific audio
 * source file (its content plus additional media data) from the actor managing
 * the currently available sources. A source is uniquely identified using the
 * medium ID and the (relative) URI within this medium.
 *
 * @param mediumID the ID of the medium the desired source belongs to
 * @param uri the URI of the desired source relative to the medium
 */
case class AudioSourceID(mediumID: String, uri: String)

/**
 * A message class containing information about an audio source that is going
 * to be downloaded.
 *
 * Via the information stored here all required information about an audio
 * source to be played by a client can be obtained. The actual audio data is
 * made available via a ''FileReaderActor'' which can be read chunk-wise. Note
 * that it is in the responsibility of the receiver of this message to stop the
 * actor when it is no longer needed.
 *
 * @param sourceID the ID of the audio source in question
 * @param contentReader a reference to a ''FileReaderActor'' for reading the audio data
 * @param length the length of the audio source (in bytes)
 */
case class AudioSourceDownloadResponse(sourceID: AudioSourceID, contentReader: ActorRef, length: Long)

/**
 * A data class representing an entry in a playlist.
 *
 * An audio player client maintains a list with objects of this class defining
 * the audio files to be played. The information contained here is sufficient
 * to request actual audio content from a media manager and initiate playback.
 *
 * @param sourceID the ID of the audio source in question
 * @param index the index of this source in the current playlist
 * @param skip the skip position
 * @param skipTime the skip time
 */
case class AudioSourcePlaylistInfo(sourceID: AudioSourceID, index: Int, skip: Long, skipTime: Long)
