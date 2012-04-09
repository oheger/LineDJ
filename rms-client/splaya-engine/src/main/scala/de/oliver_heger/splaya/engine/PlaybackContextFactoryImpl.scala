package de.oliver_heger.splaya.engine

import java.io.InputStream
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * A default implementation of the ''PlaybackContextFactory'' interface.
 */
class PlaybackContextFactoryImpl extends PlaybackContextFactory {
  /** Constant for the default buffer size. */
  private val BufferSize = 4096

  def createPlaybackContext(stream: InputStream): PlaybackContext = {
    val sourceStream = AudioSystem.getAudioInputStream(stream)
    val baseFormat = sourceStream.getFormat
    val decodedFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
      baseFormat.getSampleRate(), 16,
      baseFormat.getChannels(), baseFormat.getChannels() * 2,
      baseFormat.getSampleRate(), false);
    val decodedStream = AudioSystem.getAudioInputStream(decodedFormat, sourceStream)
    val lineInfo = new DataLine.Info(classOf[SourceDataLine], decodedFormat);
    val line = AudioSystem.getLine(lineInfo).asInstanceOf[SourceDataLine];

    PlaybackContext(line, decodedStream, calculateStreamSize(decodedStream),
      calculateBufferSize(decodedFormat))
  }

  /**
   * Determines the size of the specified audio stream. Calculates the size
   * based on the frame length and size if possible. If the size cannot be
   * determined, <code>AudioPlayerEvent.UNKNOWN_STREAM_LENGTH</code> will be
   * returned.
   *
   * @param stream the affected stream
   * @return the size of this stream
   */
  private def calculateStreamSize(stream: AudioInputStream): Long = {
    var result: Long = -1

    if (stream.getFrameLength() > 0) {
      val fmt = stream.getFormat();
      if (fmt.getFrameSize != AudioSystem.NOT_SPECIFIED) {
        result = fmt.getFrameSize() * stream.getFrameLength();
      }
    }

    result
  }

  /**
   * Determines the size of an audio playback buffer based on the given format
   * object.
   * @param format the audio format
   * @return the size of a suitable playback buffer
   */
  private[engine] def calculateBufferSize(format: AudioFormat) = {
    if (BufferSize % format.getFrameSize != 0)
      ((BufferSize / format.getFrameSize) + 1) * format.getFrameSize
    else BufferSize
  }
}
