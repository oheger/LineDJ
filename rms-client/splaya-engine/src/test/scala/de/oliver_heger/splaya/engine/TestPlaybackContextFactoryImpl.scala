package de.oliver_heger.splaya.engine

import java.io.IOException

import org.easymock.EasyMock
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.SourceDataLine

/**
 * Test class of ''PlaybackContextFactoryImpl''.
 */
class TestPlaybackContextFactoryImpl extends JUnitSuite with EasyMockSugar {
  /** Constant for the first test file. */
  private val TestFile1 = "/test.mp3"

  /** The factory to be tested. */
  private var factory: PlaybackContextFactoryImpl = _

  @Before def setUp() {
    factory = new PlaybackContextFactoryImpl
  }

  /**
   * Creates a playback context for the specified test file.
   * @param file the test file
   * @return the context created by the test factory
   */
  private def createContext(file: String): PlaybackContext =
    factory.createPlaybackContext(getClass().getResourceAsStream(file))

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
