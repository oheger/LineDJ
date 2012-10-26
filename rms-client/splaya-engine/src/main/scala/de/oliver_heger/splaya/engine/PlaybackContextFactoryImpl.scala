package de.oliver_heger.splaya.engine

import java.io.InputStream

import com.sun.media.sound.DirectAudioDeviceProvider

import de.oliver_heger.splaya.mp3.ID3Stream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider
import javazoom.spi.mpeg.sampled.file.MpegAudioFileReader

/**
 * A default implementation of the ''PlaybackContextFactory'' interface.
 */
class PlaybackContextFactoryImpl extends PlaybackContextFactoryOld {
  /** Constant for the default buffer size. */
  private val BufferSize = 4096

  def createPlaybackContext(stream: InputStream): PlaybackContext = {
    val reader = new MpegAudioFileReader
    val skipStream = new ID3Stream(stream)
    skipStream.skipID3()
    val sourceStream = reader.getAudioInputStream(skipStream)
    val baseFormat = sourceStream.getFormat
    val decodedFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
      baseFormat.getSampleRate(), 16,
      baseFormat.getChannels(), baseFormat.getChannels() * 2,
      baseFormat.getSampleRate(), false);
    val provider = new MpegFormatConversionProvider
    val decodedStream = provider.getAudioInputStream(decodedFormat, sourceStream)
    val lineInfo = new DataLine.Info(classOf[SourceDataLine], decodedFormat)
    val line = createLine(lineInfo)

    PlaybackContext(line, decodedStream, calculateStreamSize(decodedStream),
      calculateBufferSize(decodedFormat))
  }

  /**
   * Determines the size of the specified audio stream. Calculates the size
   * based on the frame length and size if possible. If the size cannot be
   * determined, a negative value is returned.
   *
   * @param stream the affected stream
   * @return the size of this stream
   */
  private def calculateStreamSize(stream: AudioInputStream): Long = {
    var result: Long = -1

    if (stream.getFrameLength() > 0) {
      val fmt = stream.getFormat();
      if (fmt.getFrameSize != AudioSystem.NOT_SPECIFIED) {
        result = fmt.getFrameSize() * stream.getFrameLength()
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

  /**
   * Creates a line object based on the given format info.
   * @param info the info object for the line to be created
   * @return the newly created line
   */
  private def createLine(info: DataLine.Info): SourceDataLine = {
    //TODO extract into its own service so that access to this internal class
    // is wrapped
    val provider = new DirectAudioDeviceProvider
    val minfos = provider.getMixerInfo
    var mixer: Option[Mixer] = None
    minfos find { minfo =>
      val mx = provider.getMixer(minfo)
      if(mx isLineSupported info) {
        mixer = Some(mx)
        true
      } else {
        false
      }
    }
    if(mixer.isEmpty) {
      throw new IllegalArgumentException("Cannot obtain line for " + info)
    }
    mixer.get.getLine(info).asInstanceOf[SourceDataLine]
  }
}
