package de.oliver_heger.splaya.engine;
import javax.sound.sampled.SourceDataLine

/**
 * A message that indicates that processing should be aborted.
 */
case class Exit

/**
 * A message for adding another file to the playlist.
 */
case class AddSourceFile(file: String)

/**
 * A message which instructs the reader actor to read another chunk copy it to
 * the target location.
 */
case class ReadChunk

/**
 * A message for playing an audio file. The message contains some information
 * about the audio file to be played.
 */
case class AudioSource(name: String, length: Long)

/**
 * A message for writing a chunk of audio data into the specified line.
 */
case class PlayChunk(line: SourceDataLine, chunk: Array[Byte], len: Int)

/**
 * A message which indicates that a full chunk of audio data was played.
 */
case class ChunkPlayed