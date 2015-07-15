package de.oliver_heger.jplaya.playlist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.VFS;
import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import de.oliver_heger.jplaya.engine.AudioPlayerEvent;
import de.oliver_heger.jplaya.engine.AudioStreamData;
import de.oliver_heger.jplaya.engine.EndAudioStreamData;
import de.oliver_heger.jplaya.engine.mediainfo.SongDataManager;
import de.oliver_heger.jplaya.playlist.CurrentPositionInfo;
import de.oliver_heger.jplaya.playlist.PlaylistController;
import de.oliver_heger.jplaya.playlist.PlaylistInfo;
import de.oliver_heger.jplaya.playlist.PlaylistManager;
import de.oliver_heger.jplaya.playlist.PlaylistManagerFactory;
import de.oliver_heger.jplaya.playlist.PlaylistOrder;

/**
 * Test class for {@code PlaylistController}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestPlaylistController extends EasyMockSupport
{
    /** Constant for the default playlist order. */
    private static final PlaylistOrder DEF_ORDER = PlaylistOrder.RANDOM;

    /** Constant for the auto save interval. */
    private static final int AUTO_SAVE = 16;

    /** Constant for the skip backwards limit. */
    private static final long SKIP_BACK_LIMIT = 5000L;

    /** Constant for the index in the playlist. */
    private static final int PLAYLIST_INDEX = 22;

    /** The index for the playlist manager for the currently played song. */
    private static final int IDX_PM_PLAYED = 0;

    /** The index for the playlist manager for the audio source. */
    private static final int IDX_PM_SOURCE = 1;

    /** Constant for the name of a test file. */
    private static final String FILE_NAME = "TestSong.mp3";

    /** Constant for the content of a test file. */
    private static final String FILE_CONTENT =
            "Content of a test file written for "
                    + TestPlaylistController.class.getName();

    /** The file system manager. */
    private static FileSystemManager manager;

    /** A helper object for creating temporary files. */
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    /** A mock for the playlist manager factory. */
    private PlaylistManagerFactory factory;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception
    {
        manager = VFS.getManager();
    }

    @Before
    public void setUp() throws Exception
    {
        factory = createMock(PlaylistManagerFactory.class);
    }

    /**
     * Creates a default test instance.
     *
     * @return the test instance
     */
    private PlaylistController createController()
    {
        return new PlaylistController(manager, factory, AUTO_SAVE,
                SKIP_BACK_LIMIT);
    }

    /**
     * Creates a default test controller and initializes its playlist.
     *
     * @return the controller
     */
    private PlaylistController createInitializedController()
    {
        PlaylistController controller = createController();
        try
        {
            controller.initializePlaylist(DEF_ORDER);
        }
        catch (IOException ioex)
        {
            fail("Unexpected exception: " + ioex);
        }
        return controller;
    }

    /**
     * Creates a mock event that expects no interaction. This mock can be used
     * to test dummy interface implementations.
     *
     * @return the mock event
     */
    private static AudioPlayerEvent mockEvent()
    {
        return mockEvent(null);
    }

    /**
     * Creates a mock event which can be queried for a stream ID, but does not
     * expect further interaction. This mock can be used to test dummy interface
     * implementations.
     *
     * @param id the ID
     * @return the mock event
     */
    private static AudioPlayerEvent mockEvent(Object id)
    {
        AudioPlayerEvent event = EasyMock.createMock(AudioPlayerEvent.class);
        if (id != null)
        {
            AudioStreamData data = EasyMock.createMock(AudioStreamData.class);
            EasyMock.expect(event.getStreamData()).andReturn(data).anyTimes();
            EasyMock.expect(data.getID()).andReturn(id).anyTimes();
            EasyMock.replay(data);
        }
        EasyMock.replay(event);
        return event;
    }

    /**
     * Tries to create an instance without a file system manager.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitNoFSManager()
    {
        new PlaylistController(null, factory, 0, 0L);
    }

    /**
     * Tries to create an instance without a factory.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitNoFactory()
    {
        new PlaylistController(manager, null, AUTO_SAVE, SKIP_BACK_LIMIT);
    }

    /**
     * Tests the error() implementation. We can only test that the event is not
     * touched.
     */
    @Test
    public void testError()
    {
        AudioPlayerEvent event = mockEvent();
        createController().error(event);
    }

    /**
     * Tests the streamStarts() implementation. We can only test that the event
     * is not touched.
     */
    @Test
    public void testStreamStarts()
    {
        AudioPlayerEvent event = mockEvent();
        createController().streamStarts(event);
    }

    /**
     * Tests the playlistEnds() implementation. We can only test that the event
     * is not touched.
     */
    @Test
    public void testPlaylistEnds()
    {
        AudioPlayerEvent event = mockEvent();
        createController().playListEnds(event);
    }

    /**
     * Prepares mock objects for the initialization of a playlist. Two playlist
     * manager mocks are created, one for the song currently played, and one for
     * the audio source implementation. They are returned as array.
     *
     * @return the mock playlist managers
     */
    private PlaylistManager[] prepareInitPlaylist()
    {
        PlaylistManager pmPlayed = createMock(PlaylistManager.class);
        PlaylistManager pmSource = createMock(PlaylistManager.class);
        try
        {
            EasyMock.expect(factory.createPlaylistManager(PlaylistOrder.RANDOM))
                    .andReturn(pmPlayed);
        }
        catch (IOException ioex)
        {
            fail("Unexpected exception: " + ioex);
        }
        EasyMock.expect(pmPlayed.copy()).andReturn(pmSource);
        return new PlaylistManager[] {
                pmPlayed, pmSource
        };
    }

    /**
     * Tests whether the auto save counter is reset when a new playlist is
     * initialized.
     */
    @Test
    public void testInitializePlaylistResetAutoSaveCounter() throws IOException
    {
        prepareInitPlaylist();
        prepareInitPlaylist();
        replayAll();
        PlaylistController controller = createInitializedController();
        controller.getAutoSaveCounter().set(20110622);
        controller.initializePlaylist(DEF_ORDER);
        assertEquals("Counter not reset", 0, controller.getAutoSaveCounter()
                .get());
        verifyAll();
    }

    /**
     * Tests the copy() implementation of the exposed playlist manager.
     */
    @Test
    public void testGetPlaylistManagerCopy() throws IOException
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        PlaylistManager copy = createMock(PlaylistManager.class);
        EasyMock.expect(pms[IDX_PM_PLAYED].copy()).andReturn(copy);
        replayAll();
        PlaylistController controller = createInitializedController();
        assertSame("Wrong copy", copy, controller.getPlaylistManager().copy());
        verifyAll();
    }

    /**
     * Tests whether the initial position can be queried from the exposed
     * playlist manager.
     */
    @Test
    public void testGetPlaylistManagerInitialPosInfo() throws IOException
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        CurrentPositionInfo posInfo =
                new CurrentPositionInfo(20110619192915L, 20110619192925L);
        EasyMock.expect(pms[IDX_PM_PLAYED].getInitialPositionInfo()).andReturn(
                posInfo);
        replayAll();
        PlaylistController controller = createInitializedController();
        assertSame("Wrong info", posInfo, controller.getPlaylistManager()
                .getInitialPositionInfo());
        verifyAll();
    }

    /**
     * Tests whether the state of the exposed playlist manager can be saved.
     */
    @Test
    public void testGetPlaylistManagerSaveState() throws IOException
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        CurrentPositionInfo posInfo =
                new CurrentPositionInfo(20110619193245L, 20110619193256L);
        pms[IDX_PM_PLAYED].saveState(posInfo);
        replayAll();
        PlaylistController controller = createInitializedController();
        controller.getPlaylistManager().saveState(posInfo);
        verifyAll();
    }

    /**
     * Tests whether information about the playlist can be queried from the
     * exposed playlist manager.
     */
    @Test
    public void testGetPlaylistManagerPlaylistInfo()
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        PlaylistInfo info = createMock(PlaylistInfo.class);
        EasyMock.expect(pms[IDX_PM_PLAYED].getPlaylistInfo()).andReturn(info);
        replayAll();
        PlaylistController controller = createInitializedController();
        assertSame("Wrong info", info, controller.getPlaylistManager()
                .getPlaylistInfo());
        verifyAll();
    }

    /**
     * Tests whether the exposed playlist manager can be moved to the next song.
     */
    @Test
    public void testGetPlaylistManagerNextSong()
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        final int idx = 11;
        EasyMock.expect(pms[IDX_PM_PLAYED].nextSong()).andReturn(Boolean.TRUE);
        EasyMock.expect(pms[IDX_PM_PLAYED].getCurrentSongIndex())
                .andReturn(idx);
        pms[IDX_PM_SOURCE].setCurrentSongIndex(idx);
        replayAll();
        PlaylistController controller = createInitializedController();
        assertTrue("Wrong result", controller.getPlaylistManager().nextSong());
        verifyAll();
    }

    /**
     * Tests whether the index of the current song can be queried from the
     * exposed playlist manager.
     */
    @Test
    public void testGetPlaylistManagerGetCurrentSongIndex()
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        final int idx = 12;
        EasyMock.expect(pms[IDX_PM_PLAYED].getCurrentSongIndex())
                .andReturn(idx);
        replayAll();
        PlaylistController controller = createInitializedController();
        assertEquals("Wrong index", idx, controller.getPlaylistManager()
                .getCurrentSongIndex());
        verifyAll();
    }

    /**
     * Tests whether the index of the current song can be changed on the exposed
     * playlist manager.
     */
    @Test
    public void testGetPlaylistManagerSetCurrentSongIndex()
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        final int idx = 42;
        pms[IDX_PM_PLAYED].setCurrentSongIndex(idx);
        pms[IDX_PM_SOURCE].setCurrentSongIndex(idx);
        replayAll();
        PlaylistController controller = createInitializedController();
        controller.getPlaylistManager().setCurrentSongIndex(idx);
        verifyAll();
    }

    /**
     * Tests whether the URI of the current song can be queried from the exposed
     * playlist manager.
     */
    @Test
    public void testGetPlaylistManagerGetCurrentSongURI()
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        final String uri = "file://testLala.mp3";
        EasyMock.expect(pms[IDX_PM_PLAYED].getCurrentSongURI()).andReturn(uri);
        replayAll();
        PlaylistController controller = createInitializedController();
        assertEquals("Wrong URI", uri, controller.getPlaylistManager()
                .getCurrentSongURI());
        verifyAll();
    }

    /**
     * Tests whether the finished status from the exposed playlist manager can
     * be queried.
     */
    @Test
    public void testGetPlaylistManagerIsFinished()
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        EasyMock.expect(pms[IDX_PM_PLAYED].isFinished()).andReturn(
                Boolean.FALSE);
        replayAll();
        PlaylistController controller = createInitializedController();
        assertFalse("Wrong result", controller.getPlaylistManager()
                .isFinished());
        verifyAll();
    }

    /**
     * Tests whether the URIs of the playlist can be queried from the exposed
     * playlist manager.
     */
    @Test
    public void testGetPlaylistManagerGetSongURIs()
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        List<String> uris = Arrays.asList("song1", "diedeldum", "diedeldei");
        EasyMock.expect(pms[IDX_PM_PLAYED].getSongURIs()).andReturn(uris);
        replayAll();
        PlaylistController controller = createInitializedController();
        assertSame("Wrong list", uris, controller.getPlaylistManager()
                .getSongURIs());
        verifyAll();
    }

    /**
     * Tests whether the playlist manager can be moved to the previous song if
     * the skip limit has not been reached.
     */
    @Test
    public void testGetPlaylistManagerPreviousSongBeforeLimit()
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        EasyMock.expect(pms[IDX_PM_PLAYED].previousSong()).andReturn(
                Boolean.TRUE);
        final int idx = 8;
        EasyMock.expect(pms[IDX_PM_PLAYED].getCurrentSongIndex())
                .andReturn(idx);
        pms[IDX_PM_SOURCE].setCurrentSongIndex(idx);
        replayAll();
        PlaylistController controller = createInitializedController();
        controller.updateCurrentPosition(SKIP_BACK_LIMIT, SKIP_BACK_LIMIT - 1);
        assertTrue("Wrong result", controller.getPlaylistManager()
                .previousSong());
        verifyAll();
    }

    /**
     * Tests whether the current song can be played again if the skip limit has
     * been reached.
     */
    @Test
    public void testGetPlaylistManagerPreviousSongAfterLimit()
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        final int idx = 8;
        EasyMock.expect(pms[IDX_PM_PLAYED].getCurrentSongIndex())
                .andReturn(idx);
        pms[IDX_PM_SOURCE].setCurrentSongIndex(idx);
        replayAll();
        PlaylistController controller = createInitializedController();
        controller.updateCurrentPosition(20110621080725L, SKIP_BACK_LIMIT);
        assertTrue("Wrong result", controller.getPlaylistManager()
                .previousSong());
        verifyAll();
    }

    /**
     * Tries to save the state before initializing the playlist.
     */
    @Test(expected = IllegalStateException.class)
    public void testSaveStateNotInitialized() throws IOException
    {
        createController().saveState();
    }

    /**
     * Tests whether the state of the playlist can be saved.
     */
    @Test
    public void testSaveState() throws IOException
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        CurrentPositionInfo posInfo =
                new CurrentPositionInfo(20110618222532L, 20110618222542L);
        pms[IDX_PM_PLAYED].saveState(posInfo);
        replayAll();
        PlaylistController controller = createController();
        controller.initializePlaylist(PlaylistOrder.RANDOM);
        controller.updateCurrentPosition(posInfo.getPosition(),
                posInfo.getTime());
        controller.saveState();
        verifyAll();
    }

    /**
     * Creates a test file which simulates a song and returns the URI to it.
     *
     * @return the URI to the test file
     */
    private String createTestFile()
    {
        PrintWriter out = null;
        try
        {
            File file = tempFolder.newFile(FILE_NAME);
            out = new PrintWriter(file);
            out.print(FILE_CONTENT);
            return file.toURI().toString();
        }
        catch (IOException ioex)
        {
            fail("Unexpected exception: " + ioex);
            return null;
        }
        finally
        {
            if (out != null)
            {
                out.close();
            }
        }
    }

    /**
     * Prepares the playlist manager mocks to expect an invocation of
     * nextAudioStream().
     *
     * @param pms the array with mock playlist managers
     * @return the URI of the test file
     */
    private String prepareNextStream(PlaylistManager[] pms)
    {
        String uri = createTestFile();
        EasyMock.expect(pms[IDX_PM_SOURCE].isFinished()).andReturn(
                Boolean.FALSE);
        EasyMock.expect(pms[IDX_PM_SOURCE].getCurrentSongURI()).andReturn(uri);
        EasyMock.expect(pms[IDX_PM_SOURCE].getCurrentSongIndex()).andReturn(
                PLAYLIST_INDEX);
        EasyMock.expect(pms[IDX_PM_SOURCE].nextSong()).andReturn(Boolean.TRUE);
        replayAll();
        return uri;
    }

    /**
     * Tests the ID of an audio stream returned by the source implementation.
     */
    @Test
    public void testNextAudioStreamID() throws InterruptedException,
            IOException
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        String uri = prepareNextStream(pms);
        PlaylistController controller = createInitializedController();
        AudioStreamData data = controller.nextAudioStream();
        assertEquals("Wrong ID", uri, data.getID());
        verifyAll();
    }

    /**
     * Tests the name of an audio stream returned by the source implementation.
     */
    @Test
    public void testNextAudioStreamName() throws InterruptedException,
            IOException
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        String uri = prepareNextStream(pms);
        PlaylistController controller = createInitializedController();
        AudioStreamData data = controller.nextAudioStream();
        assertEquals("Wrong name", uri, data.getName());
        verifyAll();
    }

    /**
     * Tests the index of an audio stream returned by the source implementation.
     */
    @Test
    public void testNextAudioStreamIndex() throws InterruptedException,
            IOException
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        prepareNextStream(pms);
        PlaylistController controller = createInitializedController();
        AudioStreamData data = controller.nextAudioStream();
        assertEquals("Wrong index", PLAYLIST_INDEX, data.getIndex());
        verifyAll();
    }

    /**
     * Tests the position of an audio stream returned by the source
     * implementation.
     */
    @Test
    public void testNextAudioStreamPosition() throws InterruptedException,
            IOException
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        prepareNextStream(pms);
        PlaylistController controller = createInitializedController();
        AudioStreamData data = controller.nextAudioStream();
        assertEquals("Wrong position", 0, data.getPosition());
        verifyAll();
    }

    /**
     * Tests the size of an audio stream returned by the source implementation.
     */
    @Test
    public void testNextAudioStreamSize() throws InterruptedException,
            IOException
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        prepareNextStream(pms);
        PlaylistController controller = createInitializedController();
        AudioStreamData data = controller.nextAudioStream();
        assertEquals("Wrong size", FILE_CONTENT.length(), data.size());
        verifyAll();
    }

    /**
     * Tests the stream returned by the source implementation.
     */
    @Test
    public void testNextAudioStreamStream() throws InterruptedException,
            IOException
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        prepareNextStream(pms);
        PlaylistController controller = createInitializedController();
        AudioStreamData data = controller.nextAudioStream();
        BufferedReader in =
                new BufferedReader(new InputStreamReader(data.getStream()));
        try
        {
            assertEquals("Wrong content", FILE_CONTENT, in.readLine());
            assertNull("Unexpected data", in.readLine());
        }
        finally
        {
            in.close();
        }
        verifyAll();
    }

    /**
     * Tries to query an audio stream before the object was initialized.
     */
    @Test(expected = IllegalStateException.class)
    public void testNextAudioStreamNotInitialized()
            throws InterruptedException, IOException
    {
        PlaylistController controller = createController();
        controller.nextAudioStream();
    }

    /**
     * Tests whether nextAudioStream() detects that the playlist is finished.
     */
    @Test
    public void testNextAudioStreamFinished() throws InterruptedException,
            IOException
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        EasyMock.expect(pms[IDX_PM_SOURCE].isFinished())
                .andReturn(Boolean.TRUE);
        replayAll();
        PlaylistController controller = createInitializedController();
        assertSame("Wrong result", EndAudioStreamData.INSTANCE,
                controller.nextAudioStream());
        verifyAll();
    }

    /**
     * Tests the positionChanged() implementation.
     */
    @Test
    public void testPositionChanged()
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        AudioPlayerEvent event = createMock(AudioPlayerEvent.class);
        AudioStreamData streamData = createMock(AudioStreamData.class);
        final long position = 20110620221945L;
        final long time = 20110620221958L;
        EasyMock.expect(event.getStreamData()).andReturn(streamData);
        EasyMock.expect(event.getPosition()).andReturn(position);
        EasyMock.expect(event.getPlaybackTime()).andReturn(time);
        EasyMock.expect(pms[IDX_PM_PLAYED].getCurrentSongURI()).andReturn(
                FILE_NAME);
        EasyMock.expect(streamData.getID()).andReturn(FILE_NAME);
        replayAll();
        PlaylistController controller = createInitializedController();
        controller.positionChanged(event);
        CurrentPositionInfo positionInfo = controller.getCurrentPosition();
        assertEquals("Wrong position", position, positionInfo.getPosition());
        assertEquals("Wrong time", time, positionInfo.getTime());
        verifyAll();
    }

    /**
     * Tests whether positionChanged() detects an invalid stream.
     */
    @Test
    public void testPositionChangedWrongStream()
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        AudioPlayerEvent event = createMock(AudioPlayerEvent.class);
        AudioStreamData streamData = createMock(AudioStreamData.class);
        EasyMock.expect(event.getStreamData()).andReturn(streamData);
        EasyMock.expect(pms[IDX_PM_PLAYED].getCurrentSongURI()).andReturn(
                FILE_NAME);
        EasyMock.expect(streamData.getID()).andReturn(FILE_NAME + "_other");
        replayAll();
        PlaylistController controller = createInitializedController();
        controller.positionChanged(event);
        CurrentPositionInfo positionInfo = controller.getCurrentPosition();
        assertEquals("Wrong position", 0, positionInfo.getPosition());
        assertEquals("Wrong time", 0, positionInfo.getTime());
        verifyAll();
    }

    /**
     * Tests processing of a stream ends event which is not related to the
     * current song.
     */
    @Test
    public void testStreamEndsOtherSong()
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        EasyMock.expect(pms[IDX_PM_PLAYED].getCurrentSongURI()).andReturn(
                FILE_NAME);
        AudioPlayerEvent event = mockEvent(FILE_NAME + "_other");
        replayAll();
        PlaylistController controller = createInitializedController();
        controller.streamEnds(event);
        assertEquals("Wrong counter value", 0, controller.getAutoSaveCounter()
                .get());
        verifyAll();
    }

    /**
     * Tests processing of a stream ends event for the current song.
     */
    @Test
    public void testStreamEndsCurrentSong()
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        EasyMock.expect(pms[IDX_PM_PLAYED].getCurrentSongURI()).andReturn(
                FILE_NAME);
        EasyMock.expect(pms[IDX_PM_PLAYED].nextSong()).andReturn(Boolean.TRUE);
        AudioPlayerEvent event = mockEvent(FILE_NAME);
        replayAll();
        PlaylistController controller = createInitializedController();
        controller.streamEnds(event);
        assertEquals("Wrong counter value", 1, controller.getAutoSaveCounter()
                .get());
        verifyAll();
    }

    /**
     * Tests whether the playlist manager is saved if the auto save count is
     * reached.
     */
    @Test
    public void testHandleAutoSaveSave() throws IOException
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        pms[IDX_PM_PLAYED].saveState(null);
        replayAll();
        AtomicInteger counter = new AtomicInteger(AUTO_SAVE - 1);
        PlaylistController controller = createInitializedController();
        controller.handleAutoSave(counter);
        assertEquals("Counter not reset", 0, counter.get());
        verifyAll();
    }

    /**
     * Tests whether an IO exception when saving the playlist manager is
     * correctly handled by the auto save mechanism.
     */
    @Test
    public void testHandleAutoSaveSaveEx() throws IOException
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        pms[IDX_PM_PLAYED].saveState(null);
        EasyMock.expectLastCall().andThrow(new IOException("Test exception!"));
        replayAll();
        AtomicInteger counter = new AtomicInteger(AUTO_SAVE - 1);
        PlaylistController controller = createInitializedController();
        controller.handleAutoSave(counter);
        assertEquals("Counter not reset", AUTO_SAVE - 1, counter.get());
        verifyAll();
    }

    /**
     * Tests whether the auto save mechanism can deal with multiple threads.
     */
    @Test
    public void testHandleAutoSaveMultiThreaded() throws IOException,
            InterruptedException
    {
        PlaylistManager[] pms = prepareInitPlaylist();
        pms[IDX_PM_PLAYED].saveState(null);
        replayAll();
        final int count = 2 * AUTO_SAVE - 1;
        Thread[] threads = new Thread[count];
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger counter = new AtomicInteger();
        final PlaylistController controller = createInitializedController();
        for (int i = 0; i < count; i++)
        {
            threads[i] = new Thread()
            {
                @Override
                public void run()
                {
                    try
                    {
                        latch.await();
                    }
                    catch (InterruptedException iex)
                    {
                        // ignore
                    }
                    controller.handleAutoSave(counter);
                }
            };
            threads[i].start();
        }
        latch.countDown();
        for (Thread t : threads)
        {
            t.join();
        }
        assertEquals("Wrong counter", AUTO_SAVE - 1, counter.get());
        verifyAll();
    }

    /**
     * Tests fetchAllSongData() if no manager is provided.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testFetchAllSongDataNoManager()
    {
        createController().fetchAllSongData(null);
    }

    /**
     * Tests whether a song data manager can be initialized to fetch the media
     * information for the whole playlist.
     */
    @Test
    public void testFetchAllSongData()
    {
        final int count = 128;
        final int currentIndex = 77;
        SongDataManager manager = createStrictMock(SongDataManager.class);
        PlaylistManager[] pms = prepareInitPlaylist();
        List<String> uris = new ArrayList<String>(count);
        for (int i = 0; i < count; i++)
        {
            uris.add(FILE_NAME + i);
        }
        EasyMock.expect(pms[IDX_PM_PLAYED].getSongURIs()).andReturn(uris);
        EasyMock.expect(pms[IDX_PM_PLAYED].getCurrentSongIndex()).andReturn(
                currentIndex);
        for (int i = currentIndex; i < count; i++)
        {
            manager.extractSongData(FILE_NAME + i, i);
        }
        for (int i = 0; i < currentIndex; i++)
        {
            manager.extractSongData(FILE_NAME + i, i);
        }
        replayAll();
        PlaylistController controller = createInitializedController();
        controller.fetchAllSongData(manager);
        verifyAll();
    }
}
