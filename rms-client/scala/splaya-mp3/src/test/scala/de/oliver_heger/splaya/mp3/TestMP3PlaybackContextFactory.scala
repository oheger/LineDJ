package de.oliver_heger.splaya.mp3

import java.io.IOException
import java.io.InputStream

import org.easymock.EasyMock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar

import de.oliver_heger.splaya.AudioSource
import de.oliver_heger.splaya.PlaybackContext
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.SourceDataLine

/**
 * Test class of ''MP3PlaybackContextFactory''.
 */
class TestMP3PlaybackContextFactory extends JUnitSuite with EasyMockSugar {
  /** Constant for a test audio source. */
  private val TestSource = AudioSource("someFile.Mp3", 1, 100, 0, 0)

  /** Constant for the first test file. */
  private val TestFile1 = "/test.mp3"

  /** The factory to be tested. */
  private var factory: MP3PlaybackContextFactory = _

  @Before def setUp() {
    factory = new MP3PlaybackContextFactory
  }

  /**
   * Obtains an input stream for the given test file.
   * @param file the name of the test file
   * @return an input stream for this file
   */
  private def streamForFile(file: String): InputStream =
    getClass().getResourceAsStream(file)

  /**
   * Creates a playback context for the specified test file.
   * @param file the test file
   * @return the context created by the test factory
   */
  private def createContext(file: String): PlaybackContext =
    factory.createPlaybackContext(streamForFile(file),
      TestSource).get

  /**
   * Tests whether a playback context can be created.
   */
  @Test def testCreatePlaybackContext() {
    val ctx = createContext(TestFile1)
    assertNotNull("No line", ctx.line)
    assertNotNull("No audio stream", ctx.stream)
    val buffer = ctx.createPlaybackBuffer()
    assert(4096 === buffer.length)
    ctx.close()
  }

  /**
   * Tests whether a playback context for a file can be created which causes
   * the JavaZoom library to fail. We test whether the workaround is correctly
   * applied.
   */
  @Test def testCreatePlaybackContextJavazoomWorkaround() {
    val ctx = createContext("/testProblematicID3.mp3")
    assertNotNull("No audio stream", ctx.stream)
    ctx.close()
  }

  /**
   * Helper method for testing whether the URI of the audio source is evaluated
   * in order to determine whether the audio file is supported.
   * @param fileName the name of the file
   */
  private def checkCreatePlaybackContextUnsupportedSource(fileName: String) {
    val source = AudioSource(fileName, 1, 200, 0, 0)
    val stream = mock[InputStream]
    EasyMock.replay(stream)
    assertFalse("Got a context",
      factory.createPlaybackContext(stream, source).isDefined)
  }

  /**
   * Tests whether an audio source with an unsupported file extension is
   * rejected.
   */
  @Test def testCreatePlaybackContextWrongExtension() {
    checkCreatePlaybackContextUnsupportedSource("test.wav")
  }

  /**
   * Tests whether an audio file with no file extension is rejected.
   */
  @Test def testCreatePlaybackContextNoExtension() {
    checkCreatePlaybackContextUnsupportedSource("test")
    checkCreatePlaybackContextUnsupportedSource("test.")
  }

  /**
   * Tests whether an IO exception when closing the audio stream is handled.
   */
  @Test def testCloseWithEx() {
    val stream = mock[AudioInputStream]
    val line = mock[SourceDataLine]
    expecting {
      stream.close()
      EasyMock.expectLastCall().andThrow(new IOException("Test exception"))
      line.close()
    }
    whenExecuting(line, stream) {
      val ctx = PlaybackContext(line, stream, 0, 4096)
      ctx.close()
    }
  }

  /**
   * Tests the algorithm for determining the size of the playback buffer.
   */
  @Test def testCalculateBufferSize() {
    val format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44.1f, 16,
      2, 17, 10, true)
    assert(4097 === factory.calculateBufferSize(format))
  }
}
