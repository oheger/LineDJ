package de.olix.playa.engine;

import java.io.File;
import java.io.IOException;

import junit.framework.TestCase;

/**
 * Test class for AudioReadMonitorImpl.
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class TestAudioReadMonitorImpl extends TestCase
{
    /** Constant for the cache directory. */
    private static final File CACHE_DIR = new File("target/cache");

    /** Constant for the sleep period. */
    private static final long SLEEP_TIME = 150;

    /** Stores the test audio buffer. */
    private AudioBufferTestImpl buffer;

    /** Stores the monitor under test. */
    private AudioReadMonitorImpl monitor;

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        buffer = new AudioBufferTestImpl(CACHE_DIR, 1024, 2);
        monitor = new AudioReadMonitorImpl(buffer);
    }

    @Override
    /**
     * Clears any used resources. Ensures that the cache directory is cleared
     * and removed.
     */
    protected void tearDown() throws Exception
    {
        buffer.close();
        buffer.clear();
        if (CACHE_DIR.exists())
        {
            assertTrue("Cache directory cannot be removed", CACHE_DIR.delete());
        }
        super.tearDown();
    }

    /**
     * Creates a buffer event of the specified type.
     *
     * @param type the event type
     * @return the event to be created
     */
    private AudioBufferEvent createEvent(AudioBufferEvent.Type type)
    {
        return new AudioBufferEvent(buffer, type);
    }

    /**
     * Sends a buffer event to the test monitor.
     *
     * @param type the type of the event
     */
    private void fireEvent(AudioBufferEvent.Type type)
    {
        monitor.bufferChanged(createEvent(type));
    }

    /**
     * Initializes a wait thread.
     *
     * @return the initialized thread
     */
    private TestWaitThread setUpWaitThread()
    {
        TestWaitThread t = new TestWaitThread();
        try
        {
            Thread.sleep(SLEEP_TIME);
        }
        catch (InterruptedException iex)
        {
            fail("Sleeping was interrupted: " + iex);
        }
        assertTrue("Thread not waiting", t.waiting);
        return t;
    }

    /**
     * Tests whether the monitor registeres itself as buffer listener.
     */
    public void testRegisterListener()
    {
        assertEquals("Wrong listener count", 1, buffer.listenerCount);
    }

    /**
     * Tests calling the constructor with a null buffer. This should cause an
     * exception.
     */
    public void testInitNullBuffer()
    {
        try
        {
            new AudioReadMonitorImpl(null);
            fail("Could create instance with null monitor!");
        }
        catch (IllegalArgumentException iex)
        {
            // ok
        }
    }

    /**
     * Tests whether a buffer full event unlocks the monitor.
     */
    public void testBufferFull()
    {
        TestWaitThread t = setUpWaitThread();
        fireEvent(AudioBufferEvent.Type.BUFFER_FULL);
        t.shutdown();
    }

    /**
     * Tests whether a buffer closed event unlocks the monitor.
     */
    public void testBufferClosed()
    {
        TestWaitThread t = setUpWaitThread();
        fireEvent(AudioBufferEvent.Type.BUFFER_CLOSED);
        t.shutdown();
    }

    /**
     * Tests whether a buffer free event locks the monitor again.
     */
    public void testBufferFree()
    {
        testBufferFull();
        fireEvent(AudioBufferEvent.Type.BUFFER_FREE);
        TestWaitThread t = setUpWaitThread();
        fireEvent(AudioBufferEvent.Type.BUFFER_FULL);
        t.shutdown();
    }

    /**
     * Tests that other, unrelated events do not affect the monitor's state.
     */
    public void testOtherEvents()
    {
        TestWaitThread t = setUpWaitThread();
        fireEvent(AudioBufferEvent.Type.CHUNK_COUNT_CHANGED);
        fireEvent(AudioBufferEvent.Type.DATA_ADDED);
        fireEvent(AudioBufferEvent.Type.BUFFER_FREE);
        assertTrue("Thread no more waiting", t.waiting);
        fireEvent(AudioBufferEvent.Type.BUFFER_FULL);
        t.shutdown();
    }

    /**
     * Tests the behavior of the monitor when the buffer is already full at
     * creation time. In this case waiting is not necessary.
     */
    public void testBufferAlreadyFull() throws IOException
    {
        buffer.close();
        buffer.clear();
        buffer = new AudioBufferTestImpl(CACHE_DIR, 1024, 2)
        {
            @Override
            public boolean isFull()
            {
                return true;
            }
        };
        monitor = new AudioReadMonitorImpl(buffer);
        monitor.waitForBufferIdle();
    }

    /**
     * A test implementation of AudioBuffer for testing whether the event
     * listener is registered.
     */
    static class AudioBufferTestImpl extends AudioBuffer
    {
        /** Stores the number of registered event listeners. */
        int listenerCount;

        public AudioBufferTestImpl(File dir, long chunkSize, int chunks)
                throws IOException
        {
            super(dir, chunkSize, chunks, true);
        }

        @Override
        public void addBufferListener(AudioBufferListener l)
        {
            super.addBufferListener(l);
            listenerCount++;
        }
    }

    /**
     * A simple test thread class for testing the monitor.
     */
    class TestWaitThread extends Thread
    {
        volatile boolean waiting;

        /**
         * Creates a new instance of <code>TestWaitThread</code> and
         * immediately starts the thread. The method will wait until the thread
         * enters the wait state.
         */
        public TestWaitThread()
        {
            start();
            initWaiting();
        }

        @Override
        public void run()
        {
            doWaiting();
            waiting = false;
        }

        /**
         * Waits until the thread terminates gracefully.
         */
        public void shutdown()
        {
            try
            {
                join();
            }
            catch (InterruptedException iex)
            {
                fail("Exception while waiting for termination: " + iex);
            }
        }

        /**
         * Waits at the buffer.
         */
        private void doWaiting()
        {
            synchronized (this)
            {
                waiting = true;
                notify();
            }
            monitor.waitForBufferIdle();
        }

        /**
         * Waits until the waiting state is reached.
         */
        private void initWaiting()
        {
            synchronized (this)
            {
                while (!waiting)
                {
                    try
                    {
                        wait();
                    }
                    catch (InterruptedException iex)
                    {
                        iex.printStackTrace();
                    }
                }
            }
        }
    }
}
