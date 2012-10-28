package de.oliver_heger.splaya.mp3

import java.io.InputStream
import java.util.Locale

import com.sun.media.sound.DirectAudioDeviceProvider

import de.oliver_heger.splaya.AudioSource
import de.oliver_heger.splaya.PlaybackContext
import de.oliver_heger.splaya.PlaybackContextFactory
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider
import javazoom.spi.mpeg.sampled.file.MpegAudioFileReader

/**
 * An implementation of the ''PlaybackContextFactory'' interface which deals
 * with MP3 files.
 *
 * This class makes direct use of classes of the
 * [[http://www.javazoom.net/index.shtml JavaZoom library]] to set up an audio
 * stream for playing MP3 files. Because this code runs as an OSGi bundle
 * the service provider interface of JavaSound does not work in a reliable way;
 * therefore it is necessary to access the classes directly.
 *
 * JavaZoom in its current version obviously has a problem with certain ID3
 * tags. Playback of such files fails with an IOException stating "Resetting
 * to invalid mark". To avoid this error, this class uses an
 * [[de.oliver_heger.splaya.mp3.ID3Stream]] and skips all ID3 frames before
 * the audio stream is constructed.
 */
class MP3PlaybackContextFactory extends PlaybackContextFactory {
  /** Constant for the default buffer size. */
  private val BufferSize = 4096

  /**
   * @inheritdoc This implementation uses classes from the JavaZoom library
   * to set up an audio stream for playing MP3 files. Only audio sources
   * with the "mp3" file extension (ignoring case) are accepted.
   */
  def createPlaybackContext(stream: InputStream, source: AudioSource): Option[PlaybackContext] = {
    if (checkSupportedFileExtension(source)) {
      Some(setUpContext(stream))
    } else None
  }

  /**
   * Creates a playback context for the specified audio stream. This method is
   * called if the audio source has been accepted (i.e. it has one of the
   * supported file extensions).
   * @param stream the input stream
   * @return a ''PlaybackContext'' for this stream
   */
  private def setUpContext(stream: InputStream): PlaybackContext = {
    val id3Stream = new ID3Stream(stream)
    id3Stream.skipID3()
    val reader = new MpegAudioFileReader
    val sourceStream = reader.getAudioInputStream(id3Stream)
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
  private[mp3] def calculateBufferSize(format: AudioFormat) = {
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
      if (mx isLineSupported info) {
        mixer = Some(mx)
        true
      } else {
        false
      }
    }
    if (mixer.isEmpty) {
      throw new IllegalArgumentException("Cannot obtain line for " + info)
    }
    mixer.get.getLine(info).asInstanceOf[SourceDataLine]
  }

  /**
   * Tests whether the URI of the given audio source has a supported file
   * extension. Only audio files accepted by this method can be processed.
   * @param src the audio source in question
   * @return a flag whether this source is supported or not
   */
  private def checkSupportedFileExtension(src: AudioSource): Boolean = {
    val pos = src.uri.lastIndexOf('.')
    pos >= 0 && src.uri.substring(pos + 1).toLowerCase(Locale.ENGLISH) == "mp3"
  }
}
