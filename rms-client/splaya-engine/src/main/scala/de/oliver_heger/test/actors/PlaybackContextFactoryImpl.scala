package de.oliver_heger.test.actors

import java.io.InputStream
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * <p>A default implementation of the {@code PlaybackContextFactory} interface.</p>
 */
class PlaybackContextFactoryImpl extends PlaybackContextFactory {

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

    PlaybackContext(line, decodedStream, calculateStreamSize(decodedStream))
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
}