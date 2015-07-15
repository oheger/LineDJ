package de.oliver_heger.jplaya.engine.mediainfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.jplaya.engine.AudioReadMonitor;
import de.oliver_heger.jplaya.engine.mediainfo.SongDataEvent;
import de.oliver_heger.jplaya.engine.mediainfo.SongDataListener;
import de.oliver_heger.jplaya.engine.mediainfo.SongDataLoader;
import de.oliver_heger.jplaya.engine.mediainfo.SongDataManager;
import de.oliver_heger.mediastore.service.SongData;

/**
 * Test class for {@code SongDataManager}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSongDataManager
{
    /** Constant for the URI of an audio file. */
    private static final String URI = "file://mylala.mp3";

    /** Constant for the ID of the test audio file. */
    private static final Object ID = 20110603211008L;

    /** A mock executor service. */
    private ExecutorService exec;

    /** A mock loader. */
    private SongDataLoader loader;

    /** A mock monitor. */
    private AudioReadMonitor monitor;

    @Before
    public void setUp() throws Exception
    {
        exec = EasyMock.createMock(ExecutorService.class);
        loader = EasyMock.createMock(SongDataLoader.class);
        monitor = EasyMock.createMock(AudioReadMonitor.class);
    }

    /**
     * Creates a default test instance.
     *
     * @return the test instance
     */
    private SongDataManagerTestImpl createManager()
    {
        return new SongDataManagerTestImpl(exec, loader, monitor);
    }

    /**
     * Tries to create an instance without an executor service.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitNoExecutor()
    {
        new SongDataManager(null, loader, monitor);
    }

    /**
     * Tries to create an instance without a loader.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitNoLoader()
    {
        new SongDataManager(exec, null, monitor);
    }

    /**
     * Tries to create an instance without a monitor.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitNoMonitor()
    {
        new SongDataManager(exec, loader, null);
    }

    /**
     * Tries to register a null listener.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testAddSongDataListenerNull()
    {
        SongDataManagerTestImpl manager = createManager();
        manager.addSongDataListener(null);
    }

    /**
     * Tests whether an event listener can be registered.
     */
    @Test
    public void testAddSongDataListener()
    {
        SongDataManagerTestImpl manager = createManager();
        SongDataListenerTestImpl l = new SongDataListenerTestImpl();
        manager.addSongDataListener(l);
        manager.fireSongDataEvent(URI, ID);
        SongDataEvent event = l.nextEvent();
        assertEquals("Wrong URI", URI, event.getMediaFileURI());
        assertEquals("Wrong ID", ID, event.getMediaFileID());
        assertSame("Wrong manager", manager, event.getManager());
        l.verify();
    }

    /**
     * Tests whether a song data listener can be removed.
     */
    @Test
    public void testRemoveSongDataListener()
    {
        SongDataManagerTestImpl manager = createManager();
        SongDataListenerTestImpl l = new SongDataListenerTestImpl();
        manager.addSongDataListener(l);
        manager.fireSongDataEvent(URI, ID);
        manager.removeSongDataListener(l);
        final String uri2 = URI + "_other";
        manager.fireSongDataEvent(uri2, null);
        SongDataEvent event = l.nextEvent();
        assertEquals("Wrong URI", URI, event.getMediaFileURI());
        l.verify();
    }

    /**
     * Helper method for checking a successful execution of the extraction task.
     *
     * @param id the ID to be passed to the task
     * @return the manager
     * @throws InterruptedException should not happen
     */
    private SongDataManager checkExtractionTask(Object id)
            throws InterruptedException
    {
        SongData data = EasyMock.createMock(SongData.class);
        monitor.waitForMediumIdle();
        EasyMock.expect(loader.extractSongData(URI)).andReturn(data);
        EasyMock.expect(exec.isShutdown()).andReturn(Boolean.FALSE).times(2);
        EasyMock.replay(exec, loader, monitor, data);
        SongDataListenerTestImpl l = new SongDataListenerTestImpl();
        SongDataManagerTestImpl manager = createManager();
        manager.addSongDataListener(l);
        Runnable task = manager.createExtractionTask(URI, id);
        task.run();
        SongDataEvent event = l.nextEvent();
        assertEquals("Wrong URI", URI, event.getMediaFileURI());
        assertEquals("Wrong ID", id, event.getMediaFileID());
        l.verify();
        assertSame("Wrong song data for URI", data, manager.getDataForFile(URI));
        EasyMock.verify(exec, loader, monitor, data);
        return manager;
    }

    /**
     * Tests whether the extraction task works as expected if a song Id is
     * passed.
     */
    @Test
    public void testExtractionTaskWithID() throws InterruptedException
    {
        SongDataManager manager = checkExtractionTask(ID);
        assertNotNull("No song data for ID", manager.getDataForID(ID));
    }

    /**
     * Tests the extraction task if no song ID is provided.
     */
    @Test
    public void testExtractionTaskNoID() throws InterruptedException
    {
        SongDataManager manager = checkExtractionTask(null);
        assertNull("Got data for null ID", manager.getDataForID(null));
    }

    /**
     * Tests the extraction task if waiting at the monitor is interrupted.
     */
    @Test
    public void testExtractionTaskInterrupted() throws InterruptedException
    {
        EasyMock.expect(exec.isShutdown()).andReturn(Boolean.FALSE);
        monitor.waitForMediumIdle();
        EasyMock.expectLastCall().andThrow(new InterruptedException());
        EasyMock.replay(exec, loader, monitor);
        SongDataListenerTestImpl l = new SongDataListenerTestImpl();
        SongDataManagerTestImpl manager = createManager();
        manager.addSongDataListener(l);
        Runnable task = manager.createExtractionTask(URI, ID);
        task.run();
        l.verify();
        assertNull("Got data", manager.getDataForFile(URI));
        assertTrue("Not interrupted", Thread.interrupted());
        EasyMock.verify(exec, loader, monitor);
    }

    /**
     * Tests the extraction task if no extraction data can be obtained.
     */
    @Test
    public void testExtractionTaskNoData() throws InterruptedException
    {
        EasyMock.expect(exec.isShutdown()).andReturn(Boolean.FALSE).times(2);
        monitor.waitForMediumIdle();
        EasyMock.expect(loader.extractSongData(URI)).andReturn(null);
        EasyMock.replay(exec, loader, monitor);
        SongDataListenerTestImpl l = new SongDataListenerTestImpl();
        SongDataManagerTestImpl manager = createManager();
        manager.addSongDataListener(l);
        Runnable task = manager.createExtractionTask(URI, ID);
        task.run();
        l.verify();
        assertNull("Got data", manager.getDataForFile(URI));
        EasyMock.verify(exec, loader, monitor);
    }

    /**
     * Tests whether an extraction task checks the shutdown flag at the
     * beginning of its execution.
     */
    @Test
    public void testExtractionTaskImmediateShutdown()
    {
        EasyMock.expect(exec.isShutdown()).andReturn(Boolean.TRUE);
        EasyMock.replay(exec, loader, monitor);
        SongDataManagerTestImpl manager = createManager();
        Runnable task = manager.createExtractionTask(URI, ID);
        task.run();
        EasyMock.verify(exec, loader, monitor);
    }

    /**
     * Tests whether an extraction task checks the shutdown flag after waiting
     * at the monitor.
     */
    @Test
    public void testExtractionTaskShutdownAfterMonitor()
            throws InterruptedException
    {
        EasyMock.expect(exec.isShutdown()).andReturn(Boolean.FALSE);
        monitor.waitForMediumIdle();
        EasyMock.expect(exec.isShutdown()).andReturn(Boolean.TRUE);
        EasyMock.replay(exec, loader, monitor);
        SongDataManagerTestImpl manager = createManager();
        Runnable task = manager.createExtractionTask(URI, ID);
        task.run();
        EasyMock.verify(exec, loader, monitor);
    }

    /**
     * Tries to obtain song data for a null URI.
     */
    @Test
    public void testGetDataForFileNull()
    {
        SongDataManagerTestImpl manager = createManager();
        assertNull("Got data", manager.getDataForFile(null));
    }

    /**
     * Tries to obtain song data for a null URI.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testExtractSongDataNoURI()
    {
        SongDataManagerTestImpl manager = createManager();
        manager.extractSongData(null, ID);
    }

    /**
     * Tests whether song data is obtained in a background thread.
     */
    @Test
    public void testExtractSongData()
    {
        SongDataManagerTestImpl manager = createManager();
        Runnable task = manager.installMockTask();
        exec.execute(task);
        EasyMock.replay(exec, loader, monitor, task);
        manager.extractSongData(URI, ID);
        EasyMock.verify(exec, loader, monitor, task);
    }

    /**
     * Tests whether the manager can be shut down successfully.
     */
    @Test
    public void testShutdownSuccess() throws InterruptedException
    {
        exec.shutdown();
        EasyMock.expect(
                exec.awaitTermination(SongDataManager.SHUTDOWN_TIME,
                        TimeUnit.SECONDS)).andReturn(Boolean.TRUE);
        EasyMock.replay(exec, loader, monitor);
        SongDataManagerTestImpl manager = createManager();
        manager.shutdown();
        EasyMock.verify(exec, loader, monitor);
    }

    /**
     * Tests shutdown() if waiting for the executor to shut down is interrupted.
     */
    @Test
    public void testShutdownWaitingInterrupted() throws InterruptedException
    {
        exec.shutdown();
        EasyMock.expect(
                exec.awaitTermination(SongDataManager.SHUTDOWN_TIME,
                        TimeUnit.SECONDS)).andThrow(
                new InterruptedException("Test exception!"));
        EasyMock.expect(exec.shutdownNow()).andReturn(null);
        EasyMock.replay(exec, loader, monitor);
        SongDataManagerTestImpl manager = createManager();
        manager.shutdown();
        EasyMock.verify(exec, loader, monitor);
        assertTrue("Not interrupted", Thread.interrupted());
    }

    /**
     * Tests shutdown() if waiting for termination times out.
     */
    @Test
    public void testShutdownTimeout() throws InterruptedException
    {
        exec.shutdown();
        EasyMock.expect(
                exec.awaitTermination(SongDataManager.SHUTDOWN_TIME,
                        TimeUnit.SECONDS)).andReturn(Boolean.FALSE);
        EasyMock.expect(exec.shutdownNow()).andReturn(null);
        EasyMock.replay(exec, loader, monitor);
        SongDataManagerTestImpl manager = createManager();
        manager.shutdown();
        EasyMock.verify(exec, loader, monitor);
    }

    /**
     * Tests that it is not possible to request new data after a shutdown.
     */
    @Test(expected = RejectedExecutionException.class)
    public void testExtractAfterShutdown()
    {
        ExecutorService service = Executors.newSingleThreadExecutor();
        SongDataManagerTestImpl manager =
                new SongDataManagerTestImpl(service, loader, monitor);
        manager.shutdown();
        manager.extractSongData(URI, ID);
    }

    /**
     * A test implementation with some mocking facilities.
     */
    private static class SongDataManagerTestImpl extends SongDataManager
    {
        /** A mock task. */
        private Runnable mockTask;

        public SongDataManagerTestImpl(ExecutorService exec,
                SongDataLoader loader, AudioReadMonitor mon)
        {
            super(exec, loader, mon);
        }

        /**
         * Creates and installs a mock task to be returned by
         * {@code createExtractionTask()}.
         *
         * @return the mock task
         */
        public Runnable installMockTask()
        {
            mockTask = EasyMock.createMock(Runnable.class);
            return mockTask;
        }

        /**
         * Either calls the super method or checks parameters and returns the
         * mock task.
         */
        @Override
        Runnable createExtractionTask(String uri, Object id)
        {
            if (mockTask != null)
            {
                assertEquals("Wrong URI", URI, uri);
                assertEquals("Wrong ID", ID, id);
                return mockTask;
            }
            return super.createExtractionTask(uri, id);
        }
    }

    /**
     * A test event listener implementation.
     */
    private static class SongDataListenerTestImpl implements SongDataListener
    {
        /** Stores the events received by the listener. */
        private final List<SongDataEvent> events =
                new LinkedList<SongDataEvent>();

        /**
         * Returns the next event received by this listener.
         *
         * @return the next event
         */
        public SongDataEvent nextEvent()
        {
            assertFalse("Too few events", events.isEmpty());
            return events.remove(0);
        }

        /**
         * Verifies that all received events have been processed.
         */
        public void verify()
        {
            assertTrue("Too many events", events.isEmpty());
        }

        /**
         * Stores the received event.
         */
        @Override
        public void songDataLoaded(SongDataEvent event)
        {
            assertNotNull("No event", event);
            events.add(event);
        }
    }
}
