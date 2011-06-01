package de.olix.playa.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.locks.Condition;

import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import de.olix.playa.engine.AudioBufferEvent.Type;

/**
 * Test class for {@code AudioReadMonitorImpl}.
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class TestAudioReadMonitorImpl
{
    /** Constant for the cache directory. */
    private static final File CACHE_DIR = new File("target/cache");

    /** Stores the test audio buffer. */
    private AudioBufferTestImpl buffer;

    /** Stores the monitor under test. */
    private AudioReadMonitorTestImpl monitor;

    @Before
    public void setUp() throws Exception
    {
        buffer = new AudioBufferTestImpl(CACHE_DIR, 1024, 2);
        monitor = new AudioReadMonitorTestImpl(buffer);
    }

    /**
     * Clears any used resources. Ensures that the cache directory is cleared
     * and removed.
     */
    @After
    public void tearDown() throws Exception
    {
        buffer.close();
        buffer.clear();
        if (CACHE_DIR.exists())
        {
            assertTrue("Cache directory cannot be removed", CACHE_DIR.delete());
        }
    }

    /**
     * Tests whether the monitor registers itself as buffer listener.
     */
    @Test
    public void testRegisterListener()
    {
        buffer.verifyListener(monitor);
    }

    /**
     * Tries to initialize an object without a buffer.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitNullBuffer()
    {
        new AudioReadMonitorImpl(null);
    }

    /**
     * Tests fetchWaitingFlag() if the flag has to be initialized from the
     * buffer.
     */
    @Test
    public void testFetchWaitingFlagInitialize()
    {
        AudioBuffer buf = EasyMock.createMock(AudioBuffer.class);
        buf.addBufferListener(EasyMock.anyObject(AudioBufferListener.class));
        EasyMock.expect(buf.isFull()).andReturn(Boolean.FALSE);
        EasyMock.replay(buf);
        monitor = new AudioReadMonitorTestImpl(buf);
        assertEquals("Wrong result", Boolean.TRUE,
                monitor.fetchAndInitWaitingFlag());
        EasyMock.verify(buf);
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
    }

    /**
     * Tests waitForMediumIdle() if the medium can be accessed directly.
     */
    @Test
    public void testWaitForMediumIdleDirect() throws InterruptedException
    {
        Condition cond = monitor.installMockCondition();
        EasyMock.replay(cond);
        monitor.setWaitingFlag(Boolean.FALSE);
        monitor.waitForMediumIdle();
        EasyMock.verify(cond);
    }

    /**
     * Tests waitForMediumIdle() if the thread has to wait.
     */
    @Test
    public void testWaitForMediumIdleWait() throws InterruptedException
    {
        Condition cond = monitor.installMockCondition();
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
        EasyMock.replay(cond);
        monitor.setWaitingFlag(Boolean.TRUE);
        monitor.waitForMediumIdle();
        EasyMock.verify(cond);
    }

    /**
     * Helper method for creating a buffer event.
     *
     * @param type the event type
     * @return the event
     */
    private AudioBufferEvent event(AudioBufferEvent.Type type)
    {
        return new AudioBufferEvent(buffer, type);
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
        monitor.setWaitingFlag(Boolean.FALSE);
        monitor.bufferChanged(event(AudioBufferEvent.Type.BUFFER_CLOSED));
        assertEquals("Threads were unlocked", 0, monitor.getUnlockCount());
    }

    /**
     * Helper method for checking whether an event of the specified type unlocks
     * the buffer.
     *
     * @param type the event type
     */
    private void checkMonitorUnlocked(AudioBufferEvent.Type type)
    {
        monitor.setWaitingFlag(Boolean.TRUE);
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
        checkMonitorUnlocked(AudioBufferEvent.Type.BUFFER_CLOSED);
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
        monitor.setWaitingFlag(Boolean.FALSE);
        monitor.bufferChanged(event(AudioBufferEvent.Type.BUFFER_FREE));
        checkWaitingFlag(Boolean.TRUE);
        assertEquals("Threads were unlocked", 0, monitor.getUnlockCount());
    }

    /**
     * Tests that events of other types do not affect the status of the monitor.
     */
    @Test
    public void testBufferChangedOtherEvents()
    {
        monitor.bufferChanged(event(AudioBufferEvent.Type.DATA_ADDED));
        monitor.bufferChanged(event(AudioBufferEvent.Type.CHUNK_COUNT_CHANGED));
        checkWaitingFlag(null);
        assertEquals("Threads were unlocked", 0, monitor.getUnlockCount());
    }

    /**
     * Tests whether the closed status of the buffer is taken into account.
     */
    @Test
    public void testBufferChangedClosed() throws IOException
    {
        buffer.close();
        monitor.bufferChanged(event(AudioBufferEvent.Type.BUFFER_FREE));
        checkWaitingFlag(Boolean.FALSE);
    }

    /**
     * A test implementation of AudioBuffer for testing whether the event
     * listener is registered.
     */
    private static class AudioBufferTestImpl extends AudioBuffer
    {
        /** The event listener registered at this buffer. */
        private AudioBufferListener listener;

        /** Stores the number of registered event listeners. */
        private int listenerCount;

        public AudioBufferTestImpl(File dir, long chunkSize, int chunks)
                throws IOException
        {
            super(dir, chunkSize, chunks, true);
        }

        /**
         * Verifies whether the expected listener has been registered at this
         * object.
         *
         * @param expected the expected listener
         */
        public void verifyListener(AudioBufferListener expected)
        {
            assertEquals("Wrong number of listeners", 1, listenerCount);
            assertEquals("Wrong listener", expected, listener);
        }

        /**
         * Records this invocation.
         */
        @Override
        public void addBufferListener(AudioBufferListener l)
        {
            super.addBufferListener(l);
            listenerCount++;
            listener = l;
        }
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

        public AudioReadMonitorTestImpl(AudioBuffer buffer)
        {
            super(buffer);
        }

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
