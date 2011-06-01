package de.olix.playa.playlist.xml;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.easymock.EasyMock;

import de.olix.playa.engine.AudioReadMonitor;
import de.olix.playa.playlist.PlaylistException;
import de.olix.playa.playlist.SongInfo;
import de.olix.playa.playlist.SongInfoCallBack;
import de.olix.playa.playlist.SongInfoProvider;
import junit.framework.TestCase;

/**
 * Test class for SongInfoLoader.
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class TestSongInfoLoader extends TestCase
{
    /** Constant for the music directory. */
    private static final File MUSIC_DIR = new File("target");

    /** Constant for the name of a playlist item. */
    private static final String ITEM_PREFIX = "MusicFile";

    /** Constant for a dummy parameter. */
    private static final String PARAMETER = "TestParameter";

    /** Constant for the sleep count. */
    private static final long SLEEP_TIME = 125;

    /** Constant for the fetch count. */
    private static final int FETCH_COUNT = 3;

    /** Stores the object to be tested. */
    private SongInfoLoader loader;

    /** Stores the mock monitor. */
    private AudioReadMonitorTestImpl mockMonitor;

    /** Stores the mock song info provider. */
    private SongInfoProviderTestImpl mockProvider;

    /** Stores the mock call back. */
    private SongInfoCallBackTestImpl mockCallBack;

    /** Stores the playlist. */
    private Playlist playlist;

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        mockMonitor = new AudioReadMonitorTestImpl();
        mockProvider = new SongInfoProviderTestImpl();
        mockCallBack = new SongInfoCallBackTestImpl();
        playlist = new Playlist();
        loader = new SongInfoLoader(mockProvider, mockMonitor, playlist,
                MUSIC_DIR, FETCH_COUNT);
    }

    /**
     * Clears the test environment. Ensures that the background thread is
     * canceled.
     */
    @Override
    protected void tearDown() throws Exception
    {
        loader.shutdown();
    }

    /**
     * Returns the name of the playlist item with the given index.
     *
     * @param idx the index
     * @return the name of this playlist item
     */
    private static String itemName(int idx)
    {
        StringBuilder buf = new StringBuilder(ITEM_PREFIX);
        buf.append(idx);
        buf.append(".mp3");
        return buf.toString();
    }

    /**
     * Returns a dummy playlist item for the specified index.
     *
     * @param idx the index
     * @return the test item for this index
     */
    private PlaylistItem item(int idx)
    {
        return new PlaylistItem(itemName(idx));
    }

    /**
     * Creates a test playlist with the given number of items.
     *
     * @param itemCount the number of items
     */
    private void setUpPlaylist(int itemCount)
    {
        for (int i = 0; i < itemCount; i++)
        {
            playlist.addItem(item(i));
        }
    }

    /**
     * Returns a URL for the playlist item with the given index.
     *
     * @param idx the index
     * @return the URL for this item
     */
    private URL itemURL(int idx)
    {
        try
        {
            return XMLPlaylistManager.fetchMediaURL(new PlaylistItem(
                    itemName(idx)), MUSIC_DIR);
        }
        catch (PlaylistException pex)
        {
            fail("Exception when determining URL: " + pex);
            return null;
        }
    }

    /**
     * Returns the stream ID for the specified item.
     *
     * @param idx the index of the item
     * @return the stream ID for this item
     */
    private Object itemStreamID(int idx)
    {
        return XMLPlaylistManager.fetchStreamID(item(idx), MUSIC_DIR);
    }

    /**
     * Creates a mock object for a song info object for the specified playlist
     * item.
     *
     * @param idx the index of the item
     * @return a mock song info object
     */
    private SongInfo createInfoMock(int idx)
    {
        SongInfo info = EasyMock.createMock(SongInfo.class);
        EasyMock.expect(info.getTitle()).andStubReturn(itemName(idx));
        return info;
    }

    /**
     * Initializes a provider mock for expecting a number of invocations.
     *
     * @param provider the mock
     * @param startIdx the start index in the playlist
     * @param count the number of calls
     * @return a list with the song info objects
     */
    private List<SongInfo> initProviderMock(SongInfoProvider provider,
            int startIdx, int count)
    {
        List<SongInfo> infos = new ArrayList<SongInfo>(count);
        for (int i = 0; i < count; i++)
        {
            int idx = startIdx + i;
            SongInfo info = createInfoMock(idx);
            try
            {
                EasyMock.expect(provider.getSongInfo(itemURL(idx))).andReturn(
                        info);
            }
            catch (PlaylistException pex)
            {
                fail("Unexpected exception " + pex);
            }
            infos.add(info);
        }
        return infos;
    }

    /**
     * Tests fetching a single song info object.
     */
    public void testFetchSongInfoOnly() throws PlaylistException
    {
        SongInfoProvider provider = EasyMock.createMock(SongInfoProvider.class);
        SongInfoCallBack callback = EasyMock.createMock(SongInfoCallBack.class);
        setUpPlaylist(1);
        SongInfo info = createInfoMock(0);
        EasyMock.expect(provider.getSongInfo(itemURL(0))).andReturn(info);
        callback.putSongInfo(itemStreamID(0), PARAMETER, info);
        EasyMock.replay(provider, callback);
        mockProvider.setProvider(provider);
        mockCallBack.setCallBack(callback);
        loader.fetchSongInfo(item(0).getFile(MUSIC_DIR), PARAMETER,
                mockCallBack);
        mockCallBack.waitForCounter(1);
        EasyMock.verify(provider, callback);
        assertEquals("Wrong number of syncs", 1, mockMonitor.count);
    }

    /**
     * Tests fetching a song info object multiple times for the same file. The
     * provider should be asked only once.
     */
    public void testFetchSongInfoTwice() throws PlaylistException
    {
        SongInfoProvider provider = EasyMock.createMock(SongInfoProvider.class);
        SongInfoCallBack callback = EasyMock.createMock(SongInfoCallBack.class);
        setUpPlaylist(1);
        SongInfo info = createInfoMock(0);
        EasyMock.expect(provider.getSongInfo(itemURL(0))).andReturn(info);
        callback.putSongInfo(itemStreamID(0), PARAMETER, info);
        callback.putSongInfo(itemStreamID(0), null, info);
        EasyMock.replay(provider, callback);
        mockProvider.setProvider(provider);
        mockCallBack.setCallBack(callback);
        loader.fetchSongInfo(item(0).getFile(MUSIC_DIR), PARAMETER,
                mockCallBack);
        loader.fetchSongInfo(item(0).getFile(MUSIC_DIR), null, mockCallBack);
        mockCallBack.waitForCounter(2);
        EasyMock.verify(provider, callback);
    }

    /**
     * Tests fetching song info objects when a playlist is defined.
     *
     */
    public void testFetchSongInfoPlaylist() throws PlaylistException
    {
        SongInfoProvider provider = EasyMock.createMock(SongInfoProvider.class);
        SongInfoCallBack callback = EasyMock.createMock(SongInfoCallBack.class);
        setUpPlaylist(10);
        List<SongInfo> infos = initProviderMock(provider, 0, FETCH_COUNT);
        callback.putSongInfo(itemStreamID(0), PARAMETER, infos.get(0));
        EasyMock.replay(provider, callback);
        mockProvider.setProvider(provider);
        mockCallBack.setCallBack(callback);
        loader.fetchSongInfo(item(0).getFile(MUSIC_DIR), PARAMETER,
                mockCallBack);
        mockCallBack.waitForCounter(1);
        mockProvider.waitForCounter(FETCH_COUNT);
        EasyMock.verify(provider, callback);
        assertEquals("Wrong number of syncs", FETCH_COUNT, mockMonitor.count);
    }

    /**
     * Tests obtaining song infos when the provider throws an exception.
     */
    public void testFetchSongInfoException() throws PlaylistException
    {
        SongInfoProvider provider = EasyMock.createMock(SongInfoProvider.class);
        SongInfoCallBack callback = EasyMock.createMock(SongInfoCallBack.class);
        setUpPlaylist(1);
        EasyMock.expect(provider.getSongInfo(itemURL(0))).andThrow(
                new PlaylistException("Error"));
        callback.putSongInfo(itemStreamID(0), PARAMETER, null);
        EasyMock.replay(provider, callback);
        mockProvider.setProvider(provider);
        mockCallBack.setCallBack(callback);
        loader.fetchSongInfo(item(0).getFile(MUSIC_DIR), PARAMETER,
                mockCallBack);
        mockCallBack.waitForCounter(1);
        EasyMock.verify(provider, callback);
    }

    /**
     * Tests obtaining a song info again, for which an exception was thrown. The
     * 2nd access should be served from the cache.
     */
    public void testFetchSongInfoExceptionRepeat() throws PlaylistException
    {
        SongInfoProvider provider = EasyMock.createMock(SongInfoProvider.class);
        SongInfoCallBack callback = EasyMock.createMock(SongInfoCallBack.class);
        setUpPlaylist(1);
        EasyMock.expect(provider.getSongInfo(itemURL(0))).andThrow(
                new PlaylistException("Error"));
        callback.putSongInfo(itemStreamID(0), PARAMETER, null);
        EasyMock.expectLastCall().times(2);
        EasyMock.replay(provider, callback);
        mockProvider.setProvider(provider);
        mockCallBack.setCallBack(callback);
        loader.fetchSongInfo(item(0).getFile(MUSIC_DIR), PARAMETER,
                mockCallBack);
        mockCallBack.waitForCounter(1);
        loader.fetchSongInfo(item(0).getFile(MUSIC_DIR), PARAMETER,
                mockCallBack);
        mockCallBack.waitForCounter(2);
        EasyMock.verify(provider, callback);
    }

    /**
     * Tests querying a song info, which was already fetched. This should cause
     * retrieving further infos.
     */
    public void testFetchSongInfoFromCache() throws Exception
    {
        SongInfoProvider provider = EasyMock.createMock(SongInfoProvider.class);
        SongInfoCallBack callback = EasyMock.createMock(SongInfoCallBack.class);
        setUpPlaylist(10);
        List<SongInfo> infos = initProviderMock(provider, 0, FETCH_COUNT);
        callback.putSongInfo(itemStreamID(0), PARAMETER, infos.get(0));
        callback.putSongInfo(itemStreamID(1), null, infos.get(1));
        EasyMock.replay(provider, callback);
        mockProvider.setProvider(provider);
        mockCallBack.setCallBack(callback);
        loader.fetchSongInfo(item(0).getFile(MUSIC_DIR), PARAMETER,
                mockCallBack);
        mockProvider.waitForCounter(FETCH_COUNT);
        loader.fetchSongInfo(item(1).getFile(MUSIC_DIR), null, mockCallBack);
        mockCallBack.waitForCounter(2);
        Thread.sleep(SLEEP_TIME);
        EasyMock.verify(provider, callback);
    }

    /**
     * Tests querying different song infos, which should cause multiple fetch
     * phases.
     */
    public void testFetchSongNextFetch() throws Exception
    {
        SongInfoProvider provider = EasyMock.createMock(SongInfoProvider.class);
        SongInfoCallBack callback = EasyMock.createMock(SongInfoCallBack.class);
        setUpPlaylist(10);
        List<SongInfo> infos = initProviderMock(provider, 0, FETCH_COUNT);
        callback.putSongInfo(itemStreamID(0), PARAMETER, infos.get(0));
        infos = initProviderMock(provider, FETCH_COUNT, FETCH_COUNT);
        callback.putSongInfo(itemStreamID(FETCH_COUNT), null, infos.get(0));
        EasyMock.replay(provider, callback);
        mockProvider.setProvider(provider);
        mockCallBack.setCallBack(callback);
        loader.fetchSongInfo(item(0).getFile(MUSIC_DIR), PARAMETER,
                mockCallBack);
        mockProvider.waitForCounter(FETCH_COUNT);
        loader.fetchSongInfo(item(FETCH_COUNT).getFile(MUSIC_DIR), null,
                mockCallBack);
        mockCallBack.waitForCounter(2);
        mockProvider.waitForCounter(2 * FETCH_COUNT);
        Thread.sleep(SLEEP_TIME);
        EasyMock.verify(provider, callback);
    }

    /**
     * Tests shutting down the loader.
     */
    public void testShutdown()
    {
        loader.shutdown();
        try
        {
            loader.fetchSongInfo(new File("Test"), null, mockCallBack);
            fail("Could fetch song info after shutdown!");
        }
        catch (IllegalStateException istex)
        {
            // ok
        }
    }

    /**
     * A dummy monitor implementation used for testing. The implementation never
     * blocks, but counts the invocations of the monitor method.
     */
    static class AudioReadMonitorTestImpl implements AudioReadMonitor
    {
        /** The number of wait invocations. */
        int count;

        public void waitForMediumIdle()
        {
            count++;
        }
    }

    /**
     * A base class for implementing asynchronous operations and waiting for
     * their end. The class maintains a counter and provides an increment
     * operation to be called by derived classes. There is a wait method that
     * will block until a given counter value is reached.
     */
    static class AsynchronousCounter
    {
        /** The internal counter. */
        int count;

        /**
         * Blocks until the internal counter reaches the given value.
         *
         * @param value the value to wait for
         */
        public synchronized void waitForCounter(int value)
        {
            while (count < value)
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

        /**
         * Increments the internal counter.
         */
        protected synchronized void increment()
        {
            count++;
            notifyAll();
        }
    }

    /**
     * A test SongInfoProvider implementation. This implementation will count
     * the invocations. Calls are delegated to a nested provider.
     */
    static class SongInfoProviderTestImpl extends AsynchronousCounter implements
            SongInfoProvider
    {
        /** Stores the real provider. */
        private SongInfoProvider provider;

        public SongInfoProvider getProvider()
        {
            return provider;
        }

        public void setProvider(SongInfoProvider provider)
        {
            this.provider = provider;
        }

        /**
         * Fetches a song info object. Delegates to the internal provider. The
         * method invocation is counted.
         */
        public SongInfo getSongInfo(URL mediaFile) throws PlaylistException
        {
            SongInfo result = getProvider().getSongInfo(mediaFile);
            increment();
            return result;
        }
    }

    /**
     * A test implementation of SongInfoCallBack used for testing the returned
     * info objects.
     */
    static class SongInfoCallBackTestImpl extends AsynchronousCounter implements
            SongInfoCallBack
    {
        /** Stores the real call back. */
        private SongInfoCallBack callBack;

        public SongInfoCallBack getCallBack()
        {
            return callBack;
        }

        public void setCallBack(SongInfoCallBack callBack)
        {
            this.callBack = callBack;
        }

        /**
         * Delegates to the real call back for checking the passed in info
         * object. The call is counted.
         */
        public void putSongInfo(Object streamID, Object param, SongInfo songInfo)
        {
            getCallBack().putSongInfo(streamID, param, songInfo);
            increment();
        }
    }
}
