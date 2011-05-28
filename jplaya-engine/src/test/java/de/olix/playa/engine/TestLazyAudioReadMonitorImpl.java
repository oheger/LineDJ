package de.olix.playa.engine;

import java.io.File;

import org.easymock.EasyMock;

import junit.framework.TestCase;

/**
 * Test class for LazyAudioReadMonitorImpl.
 * 
 * @author Oliver Heger
 * @version $Id$
 */
public class TestLazyAudioReadMonitorImpl extends TestCase
{
    /** Constant for the cache directory. */
    private static final File CACHE_DIR = new File("target/cache");

    /** Stores the internally needed audio buffer object.*/
    private AudioBuffer buffer;
    
    /** Stores the monitor to be tested.*/
    private LazyAudioReadMonitorImplTestImpl monitor;

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        buffer = new AudioBuffer(CACHE_DIR, 1024, 2, true);
        monitor = new LazyAudioReadMonitorImplTestImpl(buffer);
    }
    
    /**
     * Removes the cache directory if it exists.
     */
    @Override
    protected void tearDown() throws Exception
    {
        buffer.close();
        buffer.clear();
        assertTrue("Cache directory cannot be removed", CACHE_DIR.delete());
    }
    
    /**
     * Tests whether the correct buffer is returned.
     */
    public void testGetAudioBuffer()
    {
        assertSame("Wrong buffer", buffer, monitor.getAudioBuffer());
    }
    
    /**
     * Tries to create an instance with a null buffer. This should cause an
     * exception.
     */
    public void testInitNull()
    {
        try
        {
            new LazyAudioReadMonitorImpl(null);
            fail("Could create instance with null buffer!");
        }
        catch(IllegalArgumentException iex)
        {
            //ok
        }
    }
    
    /**
     * Tests obtaining the internal monitor.
     */
    public void testGetInternalMonitor()
    {
        assertTrue("Wrong internal monitor", monitor.getInternalMonitor() instanceof AudioReadMonitorImpl);
    }
    
    /**
     * Tests whether the internally used monitor is cached after it was created.
     */
    public void testGetInternalMonitorCached()
    {
        AudioReadMonitor m = monitor.getInternalMonitor();
        assertSame("Multiple instances created", m, monitor.getInternalMonitor());
    }
    
    /**
     * Tests whether waitForIdle() is correctly delegated to the internal
     * monitor.
     */
    public void testWaitForIdle()
    {
        AudioReadMonitor mockMonitor = EasyMock.createMock(AudioReadMonitor.class);
        mockMonitor.waitForBufferIdle();
        EasyMock.replay(mockMonitor);
        monitor.setTestMonitor(mockMonitor);
        monitor.waitForBufferIdle();
        EasyMock.verify(mockMonitor);
    }

    /**
     * A test implementation of LazyAudioReadMonitorImpl that allows injecting
     * a specific internal monitor.
     */
    static class LazyAudioReadMonitorImplTestImpl extends LazyAudioReadMonitorImpl
    {
        /** Stores the internal monitor object to be used.*/
        private AudioReadMonitor testMonitor;

        public LazyAudioReadMonitorImplTestImpl(AudioBuffer buf)
        {
            super(buf);
        }

        public AudioReadMonitor getTestMonitor()
        {
            return testMonitor;
        }

        public void setTestMonitor(AudioReadMonitor testMonitor)
        {
            this.testMonitor = testMonitor;
        }

        /**
         * Creates the internally used monitor. This implementation returns the
         * test monitor if one is set. Otherwise the inherited method will be
         * invoked.
         */
        @Override
        protected AudioReadMonitor createInternalMonitor(AudioBuffer buffer)
        {
            return (getTestMonitor() != null) ? getTestMonitor() : super.createInternalMonitor(buffer);
        }
    }
}
