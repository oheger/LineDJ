package de.oliver_heger.jplaya.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.util.concurrent.locks.Condition;

import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.jplaya.engine.AudioBuffer;
import de.oliver_heger.jplaya.engine.AudioReadMonitorImpl;
import de.oliver_heger.jplaya.engine.DataBufferEvent;
import de.oliver_heger.jplaya.engine.DataBufferEvent.Type;

/**
 * Test class for {@code AudioReadMonitorImpl}.
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class TestAudioReadMonitorImpl
{
    /** A mock for the audio buffer. */
    private AudioBuffer buffer;

    /** Stores the monitor under test. */
    private AudioReadMonitorTestImpl monitor;

    @Before
    public void setUp() throws Exception
    {
        buffer = EasyMock.createMock(AudioBuffer.class);
        monitor = new AudioReadMonitorTestImpl();
    }

    /**
     * Tests a call of the wait operation if no buffer has been set. We can only
     * check that the method returns immediately.
     */
    @Test
    public void testWaitForMediumIdleNoBuffer() throws InterruptedException
    {
        monitor.waitForMediumIdle();
    }

    /**
     * Tests whether the monitor can be associated with another buffer.
     */
    @Test
    public void testAssociateWithBufferNew()
    {
        AudioBuffer buf2 = EasyMock.createMock(AudioBuffer.class);
        buffer.addBufferListener(monitor);
        buffer.removeBufferListener(monitor);
        EasyMock.expect(buffer.isFull()).andReturn(Boolean.TRUE);
        buf2.addBufferListener(monitor);
        EasyMock.replay(buffer, buf2);
        monitor.associateWithBuffer(buffer);
        monitor.bufferChanged(event(Type.BUFFER_FULL));
        monitor.associateWithBuffer(buf2);
        assertNull("Wrong flag", monitor.getWaitingFlag());
        assertEquals("Not unlocked", 1, monitor.getUnlockCount());
        EasyMock.verify(buffer, buf2);
    }

    /**
     * Tests whether the monitor can be associated with a null buffer.
     */
    @Test
    public void testAssociateWithBufferNull()
    {
        buffer.addBufferListener(monitor);
        buffer.removeBufferListener(monitor);
        EasyMock.replay(buffer);
        monitor.associateWithBuffer(buffer);
        monitor.associateWithBuffer(null);
        EasyMock.verify(buffer);
    }

    /**
     * Tests fetchWaitingFlag() if the flag has to be initialized from the
     * buffer.
     */
    @Test
    public void testFetchWaitingFlagInitialize()
    {
        buffer.addBufferListener(monitor);
        EasyMock.expect(buffer.isFull()).andReturn(Boolean.FALSE);
        EasyMock.replay(buffer);
        monitor.associateWithBuffer(buffer);
        assertEquals("Wrong result", Boolean.TRUE,
                monitor.fetchAndInitWaitingFlag());
        assertEquals("Flag not set", Boolean.TRUE, monitor.getWaitingFlag());
        EasyMock.verify(buffer);
    }

    /**
     * Tests whether a condition object has been initialized.
     */
    @Test
    public void testGetWaitingCondition()
    {
        assertNotNull("No condition", monitor.getWaitingCondition());
    }

    /**
     * Tests whether waiting threads can be unlocked.
     */
    @Test
    public void testUnlockWaitingThreads()
    {
        Condition cond = monitor.installMockCondition();
        cond.signalAll();
        EasyMock.replay(cond);
        monitor.unlockWaitingThreads();
        EasyMock.verify(cond);
        monitor.resetWaitCondition();
    }

    /**
     * Tests waitForMediumIdle() if the medium can be accessed directly.
     */
    @Test
    public void testWaitForMediumIdleDirect() throws InterruptedException
    {
        Condition cond = monitor.installMockCondition();
        buffer.addBufferListener(monitor);
        EasyMock.replay(cond, buffer);
        monitor.setWaitingFlag(Boolean.FALSE);
        monitor.associateWithBuffer(buffer);
        monitor.waitForMediumIdle();
        EasyMock.verify(cond, buffer);
    }

    /**
     * Tests waitForMediumIdle() if the thread has to wait.
     */
    @Test
    public void testWaitForMediumIdleWait() throws InterruptedException
    {
        Condition cond = monitor.installMockCondition();
        buffer.addBufferListener(monitor);
        cond.await();
        EasyMock.expectLastCall().times(10);
        cond.await();
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>()
        {
            @Override
            public Object answer() throws Throwable
            {
                monitor.setWaitingFlag(Boolean.FALSE);
                return null;
            }
        });
        EasyMock.replay(cond, buffer);
        monitor.setWaitingFlag(Boolean.TRUE);
        monitor.associateWithBuffer(buffer);
        monitor.waitForMediumIdle();
        EasyMock.verify(cond, buffer);
    }

    /**
     * Helper method for creating a buffer event.
     *
     * @param type the event type
     * @return the event
     */
    private DataBufferEvent event(DataBufferEvent.Type type)
    {
        return new DataBufferEvent(buffer, type);
    }

    /**
     * Tests the waiting flag of the monitor.
     *
     * @param expected the expected flag value
     */
    private void checkWaitingFlag(Boolean expected)
    {
        monitor.setWaitingFlag(null);
        assertEquals("Wrong flag", expected, monitor.getWaitingFlag());
    }

    /**
     * Tests a buffer event which does not cause a change in the monitor's
     * status.
     */
    @Test
    public void testBufferChangedNoStatusChange()
    {
        buffer.addBufferListener(monitor);
        EasyMock.replay(buffer);
        monitor.setWaitingFlag(Boolean.FALSE);
        monitor.associateWithBuffer(buffer);
        monitor.bufferChanged(event(DataBufferEvent.Type.BUFFER_CLOSED));
        assertEquals("Threads were unlocked", 0, monitor.getUnlockCount());
    }

    /**
     * Helper method for checking whether an event of the specified type unlocks
     * the buffer.
     *
     * @param type the event type
     */
    private void checkMonitorUnlocked(DataBufferEvent.Type type)
    {
        buffer.addBufferListener(monitor);
        EasyMock.replay(buffer);
        monitor.setWaitingFlag(Boolean.TRUE);
        monitor.associateWithBuffer(buffer);
        monitor.bufferChanged(event(type));
        assertEquals("Threads not unlocked", 1, monitor.getUnlockCount());
        checkWaitingFlag(Boolean.FALSE);
    }

    /**
     * Tests the reaction on a buffer closed event.
     */
    @Test
    public void testBufferChangedClosedEvent()
    {
        checkMonitorUnlocked(DataBufferEvent.Type.BUFFER_CLOSED);
    }

    /**
     * Tests the reaction on a buffer full event.
     */
    @Test
    public void testBufferChangedFullEvent()
    {
        checkMonitorUnlocked(Type.BUFFER_FULL);
    }

    /**
     * Tests the reaction on a buffer free event.
     */
    @Test
    public void testBufferChangedFreeEvent()
    {
        buffer.addBufferListener(monitor);
        EasyMock.expect(buffer.isClosed()).andReturn(Boolean.FALSE);
        EasyMock.replay(buffer);
        monitor.setWaitingFlag(Boolean.FALSE);
        monitor.associateWithBuffer(buffer);
        monitor.bufferChanged(event(DataBufferEvent.Type.BUFFER_FREE));
        checkWaitingFlag(Boolean.TRUE);
        assertEquals("Threads were unlocked", 0, monitor.getUnlockCount());
    }

    /**
     * Tests that events of other types do not affect the status of the monitor.
     */
    @Test
    public void testBufferChangedOtherEvents()
    {
        buffer.addBufferListener(monitor);
        EasyMock.replay(buffer);
        monitor.associateWithBuffer(buffer);
        monitor.bufferChanged(event(DataBufferEvent.Type.DATA_ADDED));
        monitor.bufferChanged(event(DataBufferEvent.Type.CHUNK_COUNT_CHANGED));
        checkWaitingFlag(null);
        assertEquals("Threads were unlocked", 0, monitor.getUnlockCount());
    }

    /**
     * Tests whether the source buffer of the event is taken into account.
     */
    @Test
    public void testBufferChangedOtherBuffer()
    {
        monitor.bufferChanged(event(Type.BUFFER_FULL));
        assertNull("Flag was set", monitor.getWaitingFlag());
    }

    /**
     * Tests whether the closed status of the buffer is taken into account.
     */
    @Test
    public void testBufferChangedClosed() throws IOException
    {
        buffer.addBufferListener(monitor);
        EasyMock.expect(buffer.isClosed()).andReturn(Boolean.TRUE);
        EasyMock.replay(buffer);
        monitor.associateWithBuffer(buffer);
        monitor.bufferChanged(event(DataBufferEvent.Type.BUFFER_FREE));
        checkWaitingFlag(Boolean.FALSE);
    }

    /**
     * A test implementation of the monitor which provides some mocking
     * facilities.
     */
    private static class AudioReadMonitorTestImpl extends AudioReadMonitorImpl
    {
        /** A mock condition object. */
        private Condition mockCondition;

        /** The overridden waiting flag. */
        private Boolean waitingFlag;

        /** Stores the number of calls to unlockWaitingThreads(). */
        private int unlockCount;

        /**
         * Creates and installs a mock object for the wait condition.
         *
         * @return the mock condition
         */
        public Condition installMockCondition()
        {
            mockCondition = EasyMock.createMock(Condition.class);
            return mockCondition;
        }

        /**
         * Removes the mock wait condition so that the default behavior is
         * restored.
         */
        public void resetWaitCondition()
        {
            mockCondition = null;
        }

        /**
         * Sets the overridden waiting flag.
         *
         * @param f the flag value
         */
        public void setWaitingFlag(Boolean f)
        {
            waitingFlag = f;
        }

        /**
         * Returns the number of calls to unlock threads.
         *
         * @return the number of unlockWaitingThreads() calls.
         */
        public int getUnlockCount()
        {
            return unlockCount;
        }

        /**
         * Either returns a mock object or calls the super method.
         */
        @Override
        Condition getWaitingCondition()
        {
            return (mockCondition != null) ? mockCondition : super
                    .getWaitingCondition();
        }

        /**
         * Either returns the overridden flag or calls the super method.
         */
        @Override
        Boolean getWaitingFlag()
        {
            return (waitingFlag != null) ? waitingFlag : super.getWaitingFlag();
        }

        /**
         * Records this invocation.
         */
        @Override
        void unlockWaitingThreads()
        {
            super.unlockWaitingThreads();
            unlockCount++;
        }
    }
}
