package de.oliver_heger.splaya.engine

import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import org.junit.Before
import org.junit.After
import org.junit.Test
import javax.sound.sampled.SourceDataLine
import org.junit.Ignore
import org.easymock.EasyMock
import de.oliver_heger.splaya.engine.msg.PlayChunk
import de.oliver_heger.splaya.engine.msg.Gateway
import de.oliver_heger.splaya.engine.msg.Exit
import de.oliver_heger.tsthlp.WaitForExit
import de.oliver_heger.tsthlp.QueuingActor
import de.oliver_heger.splaya.engine.msg.ChunkPlayed
import de.oliver_heger.splaya.engine.msg.ActorExited

/**
 * Test class for ''LineWriteActor''.
 */
class TestLineWriteActor extends JUnitSuite with EasyMockSugar {
  /** Constant for the buffer length. */
  private val BufferLength = 4096

  /** A mock for the data line. */
  private var line: SourceDataLine = _

  /** The actor to be tested. */
  private var actor: LineWriteActor = _

  @Before def setUp() {
    Gateway.start()
    line = mock[SourceDataLine]
    actor = new LineWriteActor
    actor.start()
  }

  @After def tearDown() {
    if (actor != null) {
      actor ! Exit
    }
  }

  /**
   * Causes the test actor to exit and waits until its shutdown is complete.
   */
  private def shutdownActor() {
    val exitCmd = new WaitForExit
    if (!exitCmd.shutdownActor(actor, 5000)) {
      fail("Actor did not exit!")
    }
    actor = null
  }

  /**
   * Creates a play chunk message with the specified parameters. Other
   * properties of the message are set to defaults.
   * @param pos the current position in the stream
   * @param skip the skip position
   * @param len the length of the chunk
   * @return a corresponding ''PlayChunk'' message
   */
  private def chunkData(pos: Long, skip: Long, len: Int = BufferLength) =
    PlayChunk(line, new Array[Byte](BufferLength), len, pos, skip)

  /**
   * Creates a test actor which mocks the playback actor. It is started and
   * installed on the gateway.
   * @return the mock playback actor
   */
  private def installPlaybackActor(): QueuingActor = {
    val playbackActor = new QueuingActor
    playbackActor.start()
    Gateway += Gateway.ActorPlayback -> playbackActor
    playbackActor
  }

  /**
   * Helper method for executing a test which sends a ''PlayChunk'' message to
   * the test actor. The mock for the line must have been prepared
   * correspondingly.
   * @param data the ''PlayChunk'' message
   */
  private def executePlayChunkTest(data: PlayChunk) {
    whenExecuting(data.line) {
      actor ! data
      shutdownActor()
    }
  }

  /**
   * Helper method for executing a test which first installs a mock playback
   * actor and then sends a ''PlayChunk'' message to the test actor. The mock
   * for the line must have been prepared correspondingly.
   * @param data the ''PlayChunk'' message
   * @return the mock for the playback actor
   */
  private def executePlayChunkTestWithPlaybackActor(data: PlayChunk): QueuingActor = {
    val playbackActor = installPlaybackActor()
    executePlayChunkTest(data)
    playbackActor
  }

  /**
   * Tests whether a complete chunk is played if no skip data is set.
   */
  @Test def testPlayChunkCompletelyNoSkip() {
    val data = chunkData(0, 0)
    expecting {
      EasyMock.expect(data.line.write(data.chunk, 0, BufferLength))
        .andReturn(BufferLength)
    }
    val playbackActor = executePlayChunkTestWithPlaybackActor(data)
    playbackActor.expectMessage(ChunkPlayed(BufferLength))
    playbackActor.ensureNoMessages()
    playbackActor.shutdown()
  }

  /**
   * Tests whether a partly played chunk is handled correctly.
   */
  @Test def testPlayChunkPartly() {
    val data = chunkData(0, 0)
    val len = BufferLength / 3
    expecting {
      EasyMock.expect(data.line.write(data.chunk, 0, BufferLength))
        .andReturn(len)
    }
    val playbackActor = executePlayChunkTestWithPlaybackActor(data)
    playbackActor.expectMessage(ChunkPlayed(len))
    playbackActor.shutdown()
  }

  /**
   * Tests a play chunk operation if the chunk has to be skipped completely.
   */
  @Test def testPlayChunkSkipTotal() {
    val data = chunkData(100, Long.MaxValue)
    val playbackActor = executePlayChunkTestWithPlaybackActor(data)
    playbackActor.expectMessage(ChunkPlayed(BufferLength))
    playbackActor.shutdown()
  }

  /**
   * Tests an edge case of a skip operation.
   */
  @Test def testPlayChunkSkipTotalEdge() {
    val data = chunkData(0, BufferLength)
    val playbackActor = executePlayChunkTestWithPlaybackActor(data)
    playbackActor.expectMessage(ChunkPlayed(BufferLength))
    playbackActor.shutdown()
  }

  /**
   * Tests a play chunk operation if the first byte of the buffer has to be
   * skipped.
   */
  @Test def testPlayChunkSkipBeginning() {
    val len = BufferLength - 100
    val data = chunkData(10, 11, len)
    expecting {
      EasyMock.expect(data.line.write(data.chunk, 1, len - 1)).andReturn(len - 1)
    }
    val playbackActor = executePlayChunkTestWithPlaybackActor(data)
    playbackActor.expectMessage(ChunkPlayed(len))
    playbackActor.shutdown()
  }

  /**
   * Tests a play chunk operation if just the last byte from the buffer can be
   * played due to the skip position.
   */
  @Test def testPlayChunkSkipEnd() {
    val pos = 1000
    val skip = pos + BufferLength - 1
    val data = chunkData(pos, skip)
    expecting {
      EasyMock.expect(data.line.write(data.chunk, BufferLength-1, 1)).andReturn(1)
    }
    val playbackActor = executePlayChunkTestWithPlaybackActor(data)
    playbackActor.expectMessage(ChunkPlayed(BufferLength))
    playbackActor.shutdown()
  }

  /**
   * Tests whether the actor sends out an exit message before it goes down.
   */
  @Test def testActorExitedMessage() {
    val listener = new QueuingActor
    listener.start()
    Gateway.register(listener)
    val lineActor = actor
    shutdownActor()
    listener.expectMessage(ActorExited(lineActor))
    listener.ensureNoMessages()
    listener.shutdown()
    Gateway.unregister(listener)
  }
}
