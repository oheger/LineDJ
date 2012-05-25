package de.oliver_heger.splaya.engine.io

import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import org.junit.Assert._
import org.junit.Before
import org.junit.Test
import org.easymock.EasyMock
import org.junit.After
import de.oliver_heger.splaya.engine.msg.Gateway
import de.oliver_heger.tsthlp.QueuingActor
import de.oliver_heger.splaya.engine.msg.ReadChunk
import java.io.IOException

/**
 * Test class for ''SourceBufferManagerImpl''.
 */
class TestSourceBufferManagerImpl extends JUnitSuite with EasyMockSugar {
  /** Constant for the size of a temporary file. */
  private val FileSize = 10000L

  /** The test actor. */
  private var actor: QueuingActor = _

  /** The object to be tested. */
  private var manager: SourceBufferManagerImpl = _

  @Before def setUp() {
    actor = new QueuingActor
    actor.start()
    Gateway.start()
    Gateway += Gateway.ActorSourceRead -> actor
    manager = new SourceBufferManagerImpl
  }

  @After def tearDown() {
    actor.shutdown()
  }

  /**
   * Tests a newly created instance.
   */
  @Test def newInstance() {
    assert(manager.currentStreamReadPosition === 0)
    assert(manager.bufferSize === 0)
  }

  /**
   * Tests whether a temporary file can be passed through the buffer.
   */
  @Test def testPassTempFile() {
    val temp = mock[TempFile]
    expecting {
      EasyMock.expect(temp.length).andReturn(FileSize)
    }
    whenExecuting(temp) {
      manager append temp
      assert(FileSize === manager.bufferSize)
      assert(temp === manager.next())
      assertTrue("Received messages", actor.queue.isEmpty)
    }
  }

  /**
   * Tests whether multiple temporary files can be processed.
   */
  @Test def testPassMultipleFiles() {
    val temp1 = mock[TempFile]
    val temp2 = mock[TempFile]
    val temp3 = mock[TempFile]
    expecting {
      EasyMock.expect(temp1.length).andReturn(FileSize)
      EasyMock.expect(temp2.length).andReturn(FileSize + 1)
      EasyMock.expect(temp3.length).andReturn(FileSize + 2)
      EasyMock.expect(temp1.delete()).andReturn(true)
      EasyMock.expect(temp2.delete()).andReturn(true)
    }

    whenExecuting(temp1, temp2, temp3) {
      manager += temp1
      manager += temp2
      assert(2 * FileSize + 1 === manager.bufferSize)
      assert(temp1 === manager.next())
      manager += temp3
      assert(3 * FileSize + 3 === manager.bufferSize)
      assert(temp2 == manager.next())
      assert(temp3 == manager.next())
      actor.expectMessage(ReadChunk)
      actor.expectMessage(ReadChunk)
    }
  }

  /**
   * Helper method for testing that the buffer is empty and that there is no
   * next element.
   */
  private def checkBufferEmpty() {
    try {
      manager.next()
      fail("Got a next element!")
    } catch {
      case ioex: IOException => // ok
    }
  }

  /**
   * Tries to call next() if the buffer is empty.
   */
  @Test def testNextEmpty() {
    checkBufferEmpty()
  }

  /**
   * Tests whether an instance can be flushed before something was added.
   */
  @Test def testFlushEmpty() {
    manager.flush()
  }

  /**
   * Tests whether a flush operation removes remaining temporary files and
   * clears the state of the manager.
   */
  @Test def testFlushWithContent() {
    val temp1 = mock[TempFile]
    val temp2 = mock[TempFile]
    expecting {
      EasyMock.expect(temp1.length).andReturn(FileSize)
      EasyMock.expect(temp2.length).andReturn(FileSize + 1)
      EasyMock.expect(temp1.delete()).andReturn(true)
      EasyMock.expect(temp2.delete()).andReturn(true)
    }

    whenExecuting(temp1, temp2) {
      manager += temp1
      manager += temp2
      manager.updateCurrentStreamReadPosition(222)
      manager.next()
      manager.flush()
      assert(0 === manager.bufferSize)
      assert(0 === manager.currentStreamReadPosition)
    }
    checkBufferEmpty()
  }

  /**
   * Tests whether the stream position is tracked and affects the buffer size.
   */
  @Test def testUpdateStreamPosition() {
    val temp = mock[TempFile]
    expecting {
      EasyMock.expect(temp.length).andReturn(FileSize)
    }
    whenExecuting(temp) {
      manager += temp
      manager.next()
      manager.updateCurrentStreamReadPosition(1000)
      assert(1000 === manager.currentStreamReadPosition)
      assert(FileSize - 1000 === manager.bufferSize)
    }
  }

  /**
   * Tests the notification that a stream was fully read.
   */
  @Test def testStreamRead() {
    val temp = mock[TempFile]
    expecting {
      EasyMock.expect(temp.length).andReturn(FileSize)
    }
    whenExecuting(temp) {
      manager += temp
      manager.next()
      manager.streamRead(1000)
      manager.updateCurrentStreamReadPosition(100)
      assert(FileSize - 1000 - 100 === manager.bufferSize)
    }
  }
}
