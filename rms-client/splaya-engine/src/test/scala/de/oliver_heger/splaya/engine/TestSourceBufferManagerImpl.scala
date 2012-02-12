package de.oliver_heger.splaya.engine

import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import org.junit.Assert._
import org.junit.Before
import org.junit.Test
import org.easymock.EasyMock
import org.junit.After

/**
 * Test class for {@code SourceBufferManagerImpl}.
 */
class TestSourceBufferManagerImpl extends JUnitSuite with EasyMockSugar {
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

  /**
   * Tests whether a temporary file can be passed through the buffer.
   */
  @Test def testPassTempFile() {
    val temp = mock[TempFile]
    manager append temp
    assert(temp === manager.next())
    assertTrue("Received messages", actor.queue.isEmpty)
  }

  /**
   * Tests whether multiple temporary files can be processed.
   */
  @Test def testPassMultipleFiles() {
    val temp1 = mock[TempFile]
    val temp2 = mock[TempFile]
    val temp3 = mock[TempFile]
    expecting {
      EasyMock.expect(temp1.delete()).andReturn(true)
      EasyMock.expect(temp2.delete()).andReturn(true)
    }

    whenExecuting(temp1, temp2, temp3) {
      manager += temp1
      manager += temp2
      assert(temp1 === manager.next())
      manager += temp3
      assert(temp2 == manager.next())
      assert(temp3 == manager.next())
      actor.expectMessage(ReadChunk)
      actor.expectMessage(ReadChunk)
    }
  }

  /**
   * Tests whether an instance can be closed before something was added.
   */
  @Test def testCloseEmpty() {
    manager.close()
  }

  /**
   * Tests whether a close operation removes remaining temporary files.
   */
  @Test def testCloseWithContent() {
    val temp1 = mock[TempFile]
    val temp2 = mock[TempFile]
    expecting {
      EasyMock.expect(temp1.delete()).andReturn(true)
      EasyMock.expect(temp2.delete()).andReturn(true)
    }

    whenExecuting(temp1, temp2) {
      manager += temp1
      manager += temp2
      manager.close()
    }
  }
}
