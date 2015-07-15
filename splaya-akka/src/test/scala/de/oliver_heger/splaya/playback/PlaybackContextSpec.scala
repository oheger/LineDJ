package de.oliver_heger.splaya.playback

import java.io.InputStream
import javax.sound.sampled.{AudioFormat, SourceDataLine}

import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''PlaybackContext''.
 */
class PlaybackContextSpec extends FlatSpec with Matchers with MockitoSugar {
  /**
   * Creates a test ''PlaybackContext'' instance which uses the specified
   * audio format.
   * @param format the audio format
   * @return the test instance
   */
  private def createContext(format: AudioFormat): PlaybackContext =
    PlaybackContextImpl(mock[SourceDataLine], mock[InputStream], format)

  /**
   * Creates an ''AudioFormat'' mock object with the specified frame size.
   * @param frameSize the frame size
   * @return the mock audio format
   */
  private def createFormat(frameSize: Int): AudioFormat = {
    val format = mock[AudioFormat]
    when(format.getFrameSize).thenReturn(frameSize)
    format
  }

  "A PlaybackContext" should "use a default buffer size if possible" in {
    val context = createContext(createFormat(16))
    context.bufferSize should be(PlaybackContext.DefaultAudioBufferSize)
  }

  it should "adapt the buffer size if necessary" in {
    val context = createContext(createFormat(17))
    context.bufferSize should be(4097)
  }

  /**
   * A simple internal implementation of the PlaybackContext trait.
   * @param line the audio line
   * @param stream the stream
   * @param format the audio format
   */
  private case class PlaybackContextImpl(override val line: SourceDataLine,
                                         override val stream: InputStream,
                                         override val format: AudioFormat) extends PlaybackContext

}
