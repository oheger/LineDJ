package de.oliver_heger.jplaya.ui.mainwnd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import net.sf.jguiraffe.di.BeanContext;
import net.sf.jguiraffe.gui.app.Application;
import net.sf.jguiraffe.gui.builder.action.ActionStore;
import net.sf.jguiraffe.gui.builder.action.FormAction;
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer;
import net.sf.jguiraffe.gui.builder.window.WindowEvent;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang3.mutable.MutableInt;
import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.easymock.IAnswer;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.jplaya.ui.ConfigurationConstants;
import de.oliver_heger.mediastore.localstore.MediaStore;
import de.oliver_heger.mediastore.service.SongData;
import de.olix.playa.engine.AudioPlayer;
import de.olix.playa.engine.AudioPlayerEvent;
import de.olix.playa.engine.AudioReadMonitor;
import de.olix.playa.engine.AudioReader;
import de.olix.playa.engine.AudioStreamData;
import de.olix.playa.engine.AudioStreamSource;
import de.olix.playa.engine.DataBuffer;
import de.olix.playa.engine.mediainfo.SongDataEvent;
import de.olix.playa.engine.mediainfo.SongDataManager;
import de.olix.playa.playlist.CurrentPositionInfo;
import de.olix.playa.playlist.FSScanner;
import de.olix.playa.playlist.PlaylistController;
import de.olix.playa.playlist.PlaylistInfo;
import de.olix.playa.playlist.PlaylistManager;
import de.olix.playa.playlist.PlaylistOrder;

/**
 * Test class for {@code MainWndController}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestMainWndController extends EasyMockSupport
{
    /** Constant for the URI of a test song. */
    private static final String SONG_URI = "file://PowerSong.mp3";

    /** A mock for the bean context. */
    private BeanContext mockContext;

    /** A mock for the media store. */
    private MediaStore store;

    /** A mock for the playlist model. */
    private PlaylistModel model;

    /** The context for the model. */
    private PlaylistContext context;

    @Before
    public void setUp() throws Exception
    {
        mockContext = createMock(BeanContext.class);
        store = createMock(MediaStore.class);
        model = createMock(PlaylistModel.class);
        context = new PlaylistContext();
        EasyMock.expect(model.getPlaylistContext()).andReturn(context)
                .anyTimes();
    }

    /**
     * Creates an installs a mock for the GUI synchronizer. This mock expects an
     * asynchronous invocation with an arbitrary Runnable. The Runnable is
     * invoked.
     *
     * @param ctrl the main controller
     */
    private void setUpSynchronizer(MainWndController ctrl)
    {
        GUISynchronizer sync = createMock(GUISynchronizer.class);
        sync.asyncInvoke(EasyMock.anyObject(Runnable.class));
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>()
        {
            @Override
            public Object answer() throws Throwable
            {
                Runnable r = (Runnable) EasyMock.getCurrentArguments()[0];
                r.run();
                return null;
            }
        });
        ctrl.setSynchronizer(sync);
    }

    /**
     * Tries to create an instance without a bean context.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoContext()
    {
        new MainWndController(null, store, model);
    }

    /**
     * Tries to create an instance without a media store.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoStore()
    {
        new MainWndController(mockContext, null, model);
    }

    /**
     * Tries to create an instance without a playlist model.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoModel()
    {
        new MainWndController(mockContext, store, null);
    }

    /**
     * Tests the window activated event. We can only check that the event is not
     * touched.
     */
    @Test
    public void testWindowActivated()
    {
        WindowEvent event = createMock(WindowEvent.class);
        replayAll();
        MainWndController ctrl =
                new MainWndController(mockContext, store, model);
        ctrl.windowActivated(event);
        verifyAll();
    }

    /**
     * Tests the window closed event. We can only check that the event is not
     * touched.
     */
    @Test
    public void testWindowClosed()
    {
        WindowEvent event = createMock(WindowEvent.class);
        replayAll();
        MainWndController ctrl =
                new MainWndController(mockContext, store, model);
        ctrl.windowClosed(event);
        verifyAll();
    }

    /**
     * Tests the window closing event. We can only check that the event is not
     * touched.
     */
    @Test
    public void testWindowClosing()
    {
        WindowEvent event = createMock(WindowEvent.class);
        replayAll();
        MainWndController ctrl =
                new MainWndController(mockContext, store, model);
        ctrl.windowClosing(event);
        verifyAll();
    }

    /**
     * Tests the window deactivated event. We can only check that the event is
     * not touched.
     */
    @Test
    public void testWindowDeactivated()
    {
        WindowEvent event = createMock(WindowEvent.class);
        replayAll();
        MainWndController ctrl =
                new MainWndController(mockContext, store, model);
        ctrl.windowDeactivated(event);
        verifyAll();
    }

    /**
     * Tests the window deiconified event. We can only check that the event is
     * not touched.
     */
    @Test
    public void testWindowDeiconified()
    {
        WindowEvent event = createMock(WindowEvent.class);
        replayAll();
        MainWndController ctrl =
                new MainWndController(mockContext, store, model);
        ctrl.windowDeiconified(event);
        verifyAll();
    }

    /**
     * Tests the window iconified event. We can only check that the event is not
     * touched.
     */
    @Test
    public void testWindowIconified()
    {
        WindowEvent event = createMock(WindowEvent.class);
        replayAll();
        MainWndController ctrl =
                new MainWndController(mockContext, store, model);
        ctrl.windowIconified(event);
        verifyAll();
    }

    /**
     * Tests reaction on the window opened event if there is no configuration
     * data for setting up a playlist.
     */
    @Test
    public void testWindowOpenedNoMediaDir()
    {
        Application app = createMock(Application.class);
        Configuration config = createMock(Configuration.class);
        ActionStore actStore = createMock(ActionStore.class);
        WindowEvent event = createMock(WindowEvent.class);
        EasyMock.expect(mockContext.getBean(Application.BEAN_APPLICATION))
                .andReturn(app);
        EasyMock.expect(app.getUserConfiguration()).andReturn(config);
        EasyMock.expect(config.getString(ConfigurationConstants.PROP_MEDIA_DIR))
                .andReturn(null);
        actStore.enableGroup(MainWndController.ACTGRP_PLAYER, false);
        MainWndController ctrl =
                new MainWndController(mockContext, store, model)
                {
                    @Override
                    protected void initAudioEngine()
                    {
                        throw new UnsupportedOperationException(
                                "Unexpected method call!");
                    }
                };
        app.addShutdownListener(ctrl);
        replayAll();
        ctrl.setActionStore(actStore);
        ctrl.windowOpened(event);
        verifyAll();
    }

    /**
     * Tests reaction on the window opened event if the directory for media
     * files has been configured. In this case the audio player should be
     * started immediately.
     */
    @Test
    public void testWindowOpenedMediaDir()
    {
        Application app = createMock(Application.class);
        Configuration config = createMock(Configuration.class);
        FSScanner scanner = createMock(FSScanner.class);
        ActionStore actStore = createMock(ActionStore.class);
        FormAction action = createMock(FormAction.class);
        WindowEvent event = createMock(WindowEvent.class);
        final String mediaDir = "R:\\";
        actStore.enableGroup(MainWndController.ACTGRP_PLAYER, false);
        EasyMock.expect(mockContext.getBean(Application.BEAN_APPLICATION))
                .andReturn(app);
        EasyMock.expect(app.getUserConfiguration()).andReturn(config);
        EasyMock.expect(config.getString(ConfigurationConstants.PROP_MEDIA_DIR))
                .andReturn(mediaDir);
        scanner.setRootURI(mediaDir);
        EasyMock.expect(
                actStore.getAction(MainWndController.ACTION_INIT_PLAYLIST))
                .andReturn(action);
        action.execute(event);
        MainWndController ctrl =
                new MainWndController(mockContext, store, model);
        app.addShutdownListener(ctrl);
        replayAll();
        ctrl.setScanner(scanner);
        ctrl.setActionStore(actStore);
        ctrl.windowOpened(event);
        verifyAll();
    }

    /**
     * Helper method for checking whether the default order can be obtained from
     * a configuration object.
     *
     * @param propValue the value of the configuration property
     * @param expOrder the expected default order
     */
    private void checkGetDefaultPlaylistOrder(String propValue,
            PlaylistOrder expOrder)
    {
        Configuration config = createMock(Configuration.class);
        EasyMock.expect(
                config.getString(ConfigurationConstants.PROP_DEF_PLAYLIST_ORDER))
                .andReturn(propValue);
        replayAll();
        MainWndController ctrl =
                new MainWndController(mockContext, store, model);
        assertEquals("Wrong default order", expOrder,
                ctrl.getDefaultPlaylistOrder(config));
        verifyAll();
    }

    /**
     * Tests getDefaultPlaylistOrder() if no order is defined in the
     * configuration.
     */
    @Test
    public void testGetDefaultPlaylistOrderUndefined()
    {
        checkGetDefaultPlaylistOrder(null, PlaylistOrder.DIRECTORIES);
    }

    /**
     * Tests getDefaultPlaylistOrder() if the configuration has an invalid
     * value.
     */
    @Test
    public void testGetDefaultPlaylistOrderInvalidConfigValue()
    {
        checkGetDefaultPlaylistOrder("not a valid order!",
                PlaylistOrder.DIRECTORIES);
    }

    /**
     * Tests getDefaultPlaylistOrder() if the configuration property is set to a
     * valid value.
     */
    @Test
    public void testGetDefaultPlaylistOrderDefined()
    {
        checkGetDefaultPlaylistOrder(
                PlaylistOrder.RANDOM.name().toLowerCase(Locale.ENGLISH),
                PlaylistOrder.RANDOM);
    }

    /**
     * Tests whether an audio reader is correctly created.
     */
    @Test
    public void testCreateAudioReader()
    {
        PlaylistController plc = createMock(PlaylistController.class);
        DataBuffer buffer = createMock(DataBuffer.class);
        replayAll();
        MainWndController ctrl =
                new MainWndController(mockContext, store, model);
        ctrl.setPlaylistController(plc);
        AudioReader reader = ctrl.createAudioReader(buffer);
        assertSame("Wrong buffer", buffer, reader.getAudioBuffer());
        assertSame("Wrong source", plc, reader.getStreamSource());
        verifyAll();
    }

    /**
     * Tests a successful initialization of the audio engine.
     */
    @Test
    public void testInitAudioEngineSuccess() throws IOException
    {
        PlaylistController plc = createMock(PlaylistController.class);
        AudioPlayer player = createMock(AudioPlayer.class);
        Application app = createMock(Application.class);
        Configuration config = createMock(Configuration.class);
        PlaylistManager pm = createMock(PlaylistManager.class);
        SongDataManager sdm = createMock(SongDataManager.class);
        AudioReadMonitor monitor = createMock(AudioReadMonitor.class);
        final DataBufferStreamSource bufferSource =
                createMock(DataBufferStreamSource.class);
        final AudioReader reader = createMock(AudioReader.class);
        EasyMock.expect(mockContext.getBean(Application.BEAN_APPLICATION))
                .andReturn(app);
        EasyMock.expect(app.getUserConfiguration()).andReturn(config);
        EasyMock.expect(
                config.getString(ConfigurationConstants.PROP_DEF_PLAYLIST_ORDER))
                .andReturn(PlaylistOrder.RANDOM.name());
        plc.initializePlaylist(PlaylistOrder.RANDOM);
        EasyMock.expect(plc.getPlaylistManager()).andReturn(pm);
        EasyMock.expect(mockContext.getBean(AudioPlayer.class)).andReturn(
                player);
        EasyMock.expect(player.getAudioSource()).andReturn(bufferSource);
        final CurrentPositionInfo posInfo =
                new CurrentPositionInfo(20110627215305L, 201106272125621L);
        EasyMock.expect(pm.getInitialPositionInfo()).andReturn(posInfo);
        player.setSkipPosition(posInfo.getPosition());
        player.setSkipTime(posInfo.getTime());
        EasyMock.expect(mockContext.getBean(SongDataManager.class)).andReturn(
                sdm);
        plc.fetchAllSongData(sdm);
        MainWndController ctrl =
                new MainWndController(mockContext, store, model)
                {
                    @Override
                    protected AudioReader createAudioReader(DataBuffer buffer)
                    {
                        assertSame("Wrong buffer", bufferSource, buffer);
                        return reader;
                    }
                };
        sdm.addSongDataListener(ctrl);
        EasyMock.expect(sdm.getMonitor()).andReturn(monitor);
        monitor.associateWithBuffer(bufferSource);
        EasyMock.expect(reader.start()).andReturn(null);
        player.addAudioPlayerListener(plc);
        player.addAudioPlayerListener(ctrl);
        player.start();
        replayAll();
        ctrl.setPlaylistController(plc);
        ctrl.initAudioEngine();
        assertSame("Wrong audio player", player, ctrl.getAudioPlayer());
        assertNotNull("No synchronizer", ctrl.getSongDataSynchronizer());
        assertSame("Wrong song data manager", sdm, ctrl.getSongDataManager());
        verifyAll();
    }

    /**
     * Helper method for checking whether the enabled states of player actions
     * are correctly updated based on the given player mock object.
     *
     * @param player the mock audio player
     * @param enStart the enabled flag of the start action
     * @param enStop the enabled flag of the stop action
     * @param enNext the enabled flag of the next action
     * @param enPrev the enabled flag of the previous action
     * @param enInit the enabled flag of the initialize action
     */
    private void checkUpdatePlayerActionStates(AudioPlayer player,
            boolean enStart, boolean enStop, boolean enNext, boolean enPrev,
            boolean enInit)
    {
        ActionStore actStore = createMock(ActionStore.class);
        FormAction actStart = createMock(FormAction.class);
        FormAction actStop = createMock(FormAction.class);
        FormAction actNext = createMock(FormAction.class);
        FormAction actPrev = createMock(FormAction.class);
        FormAction actInit = createMock(FormAction.class);
        EasyMock.expect(
                actStore.getAction(MainWndController.ACTION_PLAYER_START))
                .andReturn(actStart);
        EasyMock.expect(
                actStore.getAction(MainWndController.ACTION_PLAYER_STOP))
                .andReturn(actStop);
        EasyMock.expect(
                actStore.getAction(MainWndController.ACTION_PLAYER_NEXT))
                .andReturn(actNext);
        EasyMock.expect(
                actStore.getAction(MainWndController.ACTION_PLAYER_PREV))
                .andReturn(actPrev);
        EasyMock.expect(
                actStore.getAction(MainWndController.ACTION_INIT_PLAYLIST))
                .andReturn(actInit);
        actStart.setEnabled(enStart);
        actStop.setEnabled(enStop);
        actNext.setEnabled(enNext);
        actPrev.setEnabled(enPrev);
        actInit.setEnabled(enInit);
        replayAll();
        MainWndControllerMockPlayerTestImpl ctrl =
                new MainWndControllerMockPlayerTestImpl(player);
        ctrl.setActionStore(actStore);
        ctrl.updatePlayerActionStates();
        verifyAll();
    }

    /**
     * Helper method for checking whether the enabled states of player actions
     * are correctly updated based on the given playing flag. This
     * implementation initializes an audio player mock and delegates to the
     * method with the same name.
     *
     * @param playing the playing flag of the audio player
     * @param enStart the enabled flag of the start action
     * @param enStop the enabled flag of the stop action
     * @param enNext the enabled flag of the next action
     * @param enPrev the enabled flag of the previous action
     * @param enInit the enabled flag of the initialize action
     */
    private void checkUpdatePlayerActionStates(boolean playing,
            boolean enStart, boolean enStop, boolean enNext, boolean enPrev,
            boolean enInit)
    {
        AudioPlayer player = createMock(AudioPlayer.class);
        EasyMock.expect(player.isPlaying()).andReturn(playing);
        checkUpdatePlayerActionStates(player, enStart, enStop, enNext, enPrev,
                enInit);
    }

    /**
     * Tests whether the player actions are correctly enabled if the player is
     * playing.
     */
    @Test
    public void testUpdatePlayerActionStatesPlaying()
    {
        checkUpdatePlayerActionStates(true, false, true, true, true, false);
    }

    /**
     * Tests whether the player actions are correctly enabled if the player is
     * not playing.
     */
    @Test
    public void testUpdatePlayerActionStatesNotPlaying()
    {
        checkUpdatePlayerActionStates(false, true, false, false, false, true);
    }

    /**
     * Tests whether updatePlayerActiionStates() can handle a non-existing
     * player.
     */
    @Test
    public void testUpdatePlayerActionStatesNoPlayer()
    {
        ActionStore actStore = createMock(ActionStore.class);
        FormAction act = createMock(FormAction.class);
        EasyMock.expect(
                actStore.getAction(MainWndController.ACTION_INIT_PLAYLIST))
                .andReturn(act);
        act.setEnabled(true);
        actStore.enableGroup(MainWndController.ACTGRP_PLAYER, false);
        replayAll();
        MainWndController ctrl = new MainWndControllerMockPlayerTestImpl(null);
        ctrl.setActionStore(actStore);
        ctrl.updatePlayerActionStates();
        verifyAll();
    }

    /**
     * Tests whether playback can be started.
     */
    @Test
    public void testStartPlayback()
    {
        AudioPlayer player = createMock(AudioPlayer.class);
        player.startPlayback();
        replayAll();
        MainWndControllerMockUpdateStatesTestImpl ctrl =
                new MainWndControllerMockUpdateStatesTestImpl(player);
        ctrl.startPlayback();
        ctrl.verifyUpdateStates();
        verifyAll();
    }

    /**
     * Tests whether playback can be stopped.
     */
    @Test
    public void testStopPlayback()
    {
        AudioPlayer player = createMock(AudioPlayer.class);
        player.stopPlayback();
        replayAll();
        MainWndControllerMockUpdateStatesTestImpl ctrl =
                new MainWndControllerMockUpdateStatesTestImpl(player);
        ctrl.stopPlayback();
        ctrl.verifyUpdateStates();
        verifyAll();
    }

    /**
     * Tests whether we can skip to the next song in the playlist.
     */
    @Test
    public void testSkipToNextSong()
    {
        AudioPlayer player = createMock(AudioPlayer.class);
        ActionStore actStore = createMock(ActionStore.class);
        actStore.enableGroup(MainWndController.ACTGRP_PLAYER, false);
        player.skipStream();
        replayAll();
        MainWndControllerMockUpdateStatesTestImpl ctrl =
                new MainWndControllerMockUpdateStatesTestImpl(player);
        ctrl.setActionStore(actStore);
        ctrl.skipToNextSong();
        verifyAll();
    }

    /**
     * Tests a shutdown if the objects have not been created.
     */
    @Test
    public void testShutdownPlayerAndPlaylistNotInitialized()
            throws IOException
    {
        replayAll();
        MainWndController ctrl =
                new MainWndController(mockContext, store, model);
        ctrl.shutdownPlayerAndPlaylist();
        verifyAll();
    }

    /**
     * Tests whether a shutdown is performed correctly.
     */
    @Test
    public void testShutdownPlayerAndPlaylist() throws IOException
    {
        AudioPlayer player = createMock(AudioPlayer.class);
        PlaylistController plc = createMock(PlaylistController.class);
        SongDataManager sdm = createMock(SongDataManager.class);
        DataBufferStreamSource buffer =
                createMock(DataBufferStreamSource.class);
        EasyMock.expect(player.getAudioSource()).andReturn(buffer);
        buffer.shutdown();
        player.shutdown();
        sdm.shutdown();
        plc.saveState();
        replayAll();
        MainWndControllerMockPlayerTestImpl ctrl =
                new MainWndControllerMockPlayerTestImpl(player);
        ctrl.setPlaylistController(plc);
        ctrl.installSongDataManagerMock(sdm);
        ctrl.shutdownPlayerAndPlaylist();
        verifyAll();
    }

    /**
     * Tests the implementation of the shutdown listener.
     */
    @Test
    public void testShutdownListener()
    {
        Application app = createMock(Application.class);
        replayAll();
        final MutableInt shutdownCounter = new MutableInt();
        MainWndController ctrl =
                new MainWndController(mockContext, store, model)
                {
                    @Override
                    protected void shutdownPlayerAndPlaylist()
                            throws IOException
                    {
                        shutdownCounter.increment();
                    };
                };
        ctrl.shutdown(app);
        assertEquals("Wrong number of shutdown calls", 1, shutdownCounter
                .getValue().intValue());
        verifyAll();
    }

    /**
     * Tests the shutdown listener implementation if the shutdown method throws
     * an exception. We can only check whether nothing blows up.
     */
    @Test
    public void testShutdownListenerEx()
    {
        Application app = createMock(Application.class);
        replayAll();
        MainWndController ctrl =
                new MainWndController(mockContext, store, model)
                {
                    @Override
                    protected void shutdownPlayerAndPlaylist()
                            throws IOException
                    {
                        throw new IOException("Test exception!");
                    };
                };
        ctrl.shutdown(app);
        verifyAll();
    }

    /**
     * Tests the canShutdown() implementation.
     */
    @Test
    public void testCanShutdown()
    {
        Application app = createMock(Application.class);
        replayAll();
        MainWndController ctrl =
                new MainWndController(mockContext, store, model);
        assertTrue("Wrong result", ctrl.canShutdown(app));
        verifyAll();
    }

    /**
     * Tests whether stream end events for skipped songs are ignored.
     */
    @Test
    public void testStreamEndsSkipped()
    {
        AudioPlayerEvent event = createMock(AudioPlayerEvent.class);
        SongDataSynchronizer sync = createMock(SongDataSynchronizer.class);
        EasyMock.expect(event.isSkipped()).andReturn(Boolean.TRUE);
        replayAll();
        MainWndControllerMockPlayerTestImpl ctrl =
                new MainWndControllerMockPlayerTestImpl(null);
        ctrl.installSongDataSynchronizerMock(sync);
        ctrl.streamEnds(event);
        verifyAll();
    }

    /**
     * Tests streamEnds() if song information is available.
     */
    @Test
    public void testStreamEndsSongDataAvailable()
    {
        AudioPlayerEvent event = createMock(AudioPlayerEvent.class);
        SongDataSynchronizer sync = createMock(SongDataSynchronizer.class);
        SongDataManager sdm = createMock(SongDataManager.class);
        AudioStreamData streamData = createMock(AudioStreamData.class);
        SongData data = createMock(SongData.class);
        final int playCount = 3;
        EasyMock.expect(event.isSkipped()).andReturn(Boolean.FALSE);
        EasyMock.expect(event.getStreamData()).andReturn(streamData);
        EasyMock.expect(streamData.getID()).andReturn(SONG_URI).anyTimes();
        EasyMock.expect(sync.songPlayedEventReceived(SONG_URI)).andReturn(
                playCount);
        EasyMock.expect(sdm.getDataForFile(SONG_URI)).andReturn(data);
        data.setPlayCount(playCount);
        store.updateSongData(data);
        replayAll();
        MainWndControllerMockPlayerTestImpl ctrl =
                new MainWndControllerMockPlayerTestImpl(null);
        ctrl.installSongDataSynchronizerMock(sync);
        ctrl.installSongDataManagerMock(sdm);
        ctrl.streamEnds(event);
        verifyAll();
    }

    /**
     * Tests a stream end event if no song data is available.
     */
    @Test
    public void testStreamEndsNoSongData()
    {
        AudioPlayerEvent event = createMock(AudioPlayerEvent.class);
        SongDataSynchronizer sync = createMock(SongDataSynchronizer.class);
        AudioStreamData streamData = createMock(AudioStreamData.class);
        EasyMock.expect(event.isSkipped()).andReturn(Boolean.FALSE);
        EasyMock.expect(event.getStreamData()).andReturn(streamData);
        EasyMock.expect(streamData.getID()).andReturn(SONG_URI).anyTimes();
        EasyMock.expect(sync.songPlayedEventReceived(SONG_URI)).andReturn(0);
        replayAll();
        MainWndControllerMockPlayerTestImpl ctrl =
                new MainWndControllerMockPlayerTestImpl(null);
        ctrl.installSongDataSynchronizerMock(sync);
        ctrl.streamEnds(event);
        verifyAll();
    }

    /**
     * Tests whether a song data loaded event is correctly processed if the
     * media store does not have to be updated.
     */
    @Test
    public void testSongDataLoadedNoMediaStoreUpdate()
    {
        SongDataEvent event = createMock(SongDataEvent.class);
        SongDataSynchronizer sync = createMock(SongDataSynchronizer.class);
        model.processSongDataEvent(event);
        MainWndControllerMockPlayerTestImpl ctrl =
                new MainWndControllerMockPlayerTestImpl(null);
        setUpSynchronizer(ctrl);
        EasyMock.expect(event.getMediaFileURI()).andReturn(SONG_URI);
        EasyMock.expect(sync.songDataEventReceived(SONG_URI)).andReturn(0);
        replayAll();
        ctrl.installSongDataSynchronizerMock(sync);
        ctrl.songDataLoaded(event);
        verifyAll();
    }

    /**
     * Tests whether a song data loaded event is correctly processed if the
     * media store has to be updated.
     */
    @Test
    public void testSongDataLoadedWithMediaStoreUpdate()
    {
        SongDataEvent event = createMock(SongDataEvent.class);
        SongDataSynchronizer sync = createMock(SongDataSynchronizer.class);
        SongData data = createMock(SongData.class);
        SongDataManager manager = createMock(SongDataManager.class);
        MainWndControllerMockPlayerTestImpl ctrl =
                new MainWndControllerMockPlayerTestImpl(null);
        final int playCount = 2;
        model.processSongDataEvent(event);
        setUpSynchronizer(ctrl);
        EasyMock.expect(event.getMediaFileURI()).andReturn(SONG_URI);
        EasyMock.expect(sync.songDataEventReceived(SONG_URI)).andReturn(
                playCount);
        EasyMock.expect(manager.getDataForFile(SONG_URI)).andReturn(data);
        data.setPlayCount(playCount);
        store.updateSongData(data);
        replayAll();
        ctrl.installSongDataManagerMock(manager);
        ctrl.installSongDataSynchronizerMock(sync);
        ctrl.songDataLoaded(event);
        verifyAll();
    }

    /**
     * Tests whether a playlist end event is correctly processed.
     */
    @Test
    public void testPlaylistEnds()
    {
        ActionStore actStore = createMock(ActionStore.class);
        FormAction action = createMock(FormAction.class);
        SongDataManager sdm = createMock(SongDataManager.class);
        AudioPlayerEvent event = createMock(AudioPlayerEvent.class);
        actStore.enableGroup(MainWndController.ACTGRP_PLAYER, false);
        EasyMock.expect(
                actStore.getAction(MainWndController.ACTION_INIT_PLAYLIST))
                .andReturn(action);
        action.setEnabled(true);
        model.updateUI(sdm);
        EasyMock.expectLastCall().andAnswer(new CheckContextAnswer(-1, 0));
        MainWndControllerMockPlayerTestImpl ctrl =
                new MainWndControllerMockPlayerTestImpl(null);
        setUpSynchronizer(ctrl);
        replayAll();
        ctrl.setActionStore(actStore);
        ctrl.installSongDataManagerMock(sdm);
        ctrl.playListEnds(event);
        verifyAll();
    }

    /**
     * Tests whether a position changed event is correctly processed.
     */
    @Test
    public void testPositionChanged()
    {
        SongDataManager sdm = createMock(SongDataManager.class);
        AudioPlayerEvent event = createMock(AudioPlayerEvent.class);
        AudioStreamData data = createMock(AudioStreamData.class);
        final long time = 20110713221414L;
        final int index = 42;
        final int relpos = 75;
        EasyMock.expect(event.getPlaybackTime()).andReturn(time);
        EasyMock.expect(event.getRelativePosition()).andReturn(relpos);
        EasyMock.expect(event.getStreamData()).andReturn(data);
        EasyMock.expect(data.getIndex()).andReturn(index);
        MainWndControllerMockPlayerTestImpl ctrl =
                new MainWndControllerMockPlayerTestImpl(null);
        setUpSynchronizer(ctrl);
        model.updateUI(sdm);
        EasyMock.expectLastCall()
                .andAnswer(new CheckContextAnswer(index, time));
        replayAll();
        ctrl.installSongDataManagerMock(sdm);
        ctrl.positionChanged(event);
        assertEquals("Wrong relative position", relpos,
                context.getPlaybackRatio());
        verifyAll();
    }

    /**
     * Tests whether a stream start event is correctly processed.
     */
    @Test
    public void testStreamStarts()
    {
        SongDataManager sdm = createMock(SongDataManager.class);
        AudioPlayerEvent event = createMock(AudioPlayerEvent.class);
        AudioStreamData data = createMock(AudioStreamData.class);
        final int index = 42;
        EasyMock.expect(event.getStreamData()).andReturn(data);
        EasyMock.expect(data.getIndex()).andReturn(index);
        MainWndControllerMockUpdateStatesTestImpl ctrl =
                new MainWndControllerMockUpdateStatesTestImpl(null);
        setUpSynchronizer(ctrl);
        model.updateUI(sdm);
        EasyMock.expectLastCall().andAnswer(new CheckContextAnswer(index, 0));
        replayAll();
        ctrl.installSongDataManagerMock(sdm);
        context.setPlaybackRatio(100);
        ctrl.streamStarts(event);
        assertEquals("Wrong relative position", 0, context.getPlaybackRatio());
        assertEquals("Wrong update calls", 1, ctrl.getUpdateStatesCount());
        verifyAll();
    }

    /**
     * Tests whether the playlist UI can be reset.
     */
    @Test
    public void testResetPlaylistUI()
    {
        SongDataManager sdm = createMock(SongDataManager.class);
        MainWndControllerMockUpdateStatesTestImpl ctrl =
                new MainWndControllerMockUpdateStatesTestImpl(null);
        setUpSynchronizer(ctrl);
        model.updateUI(sdm);
        EasyMock.expectLastCall().andAnswer(new CheckContextAnswer(-1, 0));
        replayAll();
        ctrl.installSongDataManagerMock(sdm);
        context.setPlaybackRatio(100);
        ctrl.resetPlaylistUI();
        assertSame("Wrong playlist info", NullPlaylistInfo.INSTANCE,
                context.getPlaylistInfo());
        assertEquals("Wrong relative position", 0, context.getPlaybackRatio());
        verifyAll();
    }

    /**
     * Tests whether the playlist items for the playlist model can be created.
     */
    @Test
    public void testSetupPlaylistItems()
    {
        PlaylistController plc = createMock(PlaylistController.class);
        PlaylistManager plman = createMock(PlaylistManager.class);
        List<String> songUris = Collections.singletonList(SONG_URI);
        List<PlaylistItem> items =
                Collections.singletonList(new PlaylistItem());
        EasyMock.expect(plc.getPlaylistManager()).andReturn(plman);
        EasyMock.expect(plman.getSongURIs()).andReturn(songUris);
        EasyMock.expect(model.createModelItems(songUris)).andReturn(items);
        replayAll();
        MainWndController ctrl =
                new MainWndController(mockContext, store, model);
        ctrl.setPlaylistController(plc);
        assertSame("Wrong item list", items, ctrl.setUpPlaylistItems());
        verifyAll();
    }

    /**
     * Tests whether the playlist model is correctly initialized for a new
     * playlist.
     */
    @Test
    public void testInitializePlaylistModel()
    {
        PlaylistController plc = createMock(PlaylistController.class);
        SongDataManager sdm = createMock(SongDataManager.class);
        PlaylistManager plm = createMock(PlaylistManager.class);
        PlaylistInfo info = createMock(PlaylistInfo.class);
        List<PlaylistItem> items =
                Collections.singletonList(new PlaylistItem());
        EasyMock.expect(plc.getPlaylistManager()).andReturn(plm);
        EasyMock.expect(plm.getPlaylistInfo()).andReturn(info);
        model.initialize(sdm, items);
        replayAll();
        MainWndControllerMockPlayerTestImpl ctrl =
                new MainWndControllerMockPlayerTestImpl(null);
        ctrl.setPlaylistController(plc);
        ctrl.installSongDataManagerMock(sdm);
        ctrl.initializePlaylistModel(items);
        assertSame("Playlist info not set", info, context.getPlaylistInfo());
        verifyAll();
    }

    /**
     * An interface which combines the data buffer with the audio stream source
     * interface. This is needed because the source of the player is also a data
     * buffer.
     */
    private static interface DataBufferStreamSource extends DataBuffer,
            AudioStreamSource
    {
    }

    /**
     * A test implementation of the controller which allows mocking the audio
     * player. Some other helper objects can also be mocked.
     */
    private class MainWndControllerMockPlayerTestImpl extends MainWndController
    {
        /** The mock for the audio player. */
        private final AudioPlayer mockPlayer;

        /** A mock for the song data manager. */
        private SongDataManager mockSongManager;

        /** A mock for the song data synchronizer. */
        private SongDataSynchronizer mockSynchonizer;

        /**
         * Creates a new instance of {@code MainWndControllerMockPlayerTestImpl}
         * and sets the mock for the audio player.
         *
         * @param player the player mock
         */
        public MainWndControllerMockPlayerTestImpl(AudioPlayer player)
        {
            super(mockContext, store, model);
            mockPlayer = player;
        }

        /**
         * Installs a mock for the song data manager.
         *
         * @param sdm the mock
         */
        public void installSongDataManagerMock(SongDataManager sdm)
        {
            mockSongManager = sdm;
        }

        public void installSongDataSynchronizerMock(SongDataSynchronizer sync)
        {
            mockSynchonizer = sync;
        }

        /**
         * Returns the mock audio player.
         */
        @Override
        protected AudioPlayer getAudioPlayer()
        {
            return mockPlayer;
        }

        /**
         * Either returns the mock object or calls the super method.
         */
        @Override
        protected SongDataManager getSongDataManager()
        {
            return (mockSongManager != null) ? mockSongManager : super
                    .getSongDataManager();
        }

        /**
         * Either returns the mock object or calls the super method.
         */
        @Override
        SongDataSynchronizer getSongDataSynchronizer()
        {
            return (mockSynchonizer != null) ? mockSynchonizer : super
                    .getSongDataSynchronizer();
        }
    }

    /**
     * A test implementation of the controller which allows mocking the audio
     * player and changing of action states.
     */
    private class MainWndControllerMockUpdateStatesTestImpl extends
            MainWndControllerMockPlayerTestImpl
    {
        /** A counter for invocations of the update states method. */
        private int updateStatesCount;

        /**
         * Creates a new instance of
         * {@code MainWndControllerMockUpdateStatesTestImpl} and sets the mock
         * for the audio player.
         *
         * @param player the player mock
         */
        public MainWndControllerMockUpdateStatesTestImpl(AudioPlayer player)
        {
            super(player);
        }

        /**
         * Returns the number of times the method for updating action states was
         * called.
         *
         * @return the count of update states invocations
         */
        public int getUpdateStatesCount()
        {
            return updateStatesCount;
        }

        /**
         * Convenience method which checks whether the update states method was
         * called exactly once.
         */
        public void verifyUpdateStates()
        {
            assertEquals("Wrong number of update states invocations", 1,
                    getUpdateStatesCount());
        }

        /**
         * Records this invocation.
         */
        @Override
        protected void updatePlayerActionStates()
        {
            updateStatesCount++;
        }
    }

    /**
     * A specialized answer implementation that is used for testing whether
     * correct properties are set in the context when the playlist model is
     * invoked.
     */
    private class CheckContextAnswer implements IAnswer<Object>
    {
        /** The expected index. */
        private final int expIndex;

        /** The expected time. */
        private final long expTime;

        /**
         * Creates a new instance of {@code CheckContextAnswer} and initializes
         * it with the properties to check.
         *
         * @param idx the expected index
         * @param time the expected time position
         */
        public CheckContextAnswer(int idx, long time)
        {
            expIndex = idx;
            expTime = time;
        }

        /**
         * This implementation checks whether the context contains the expected
         * values.
         */
        @Override
        public Object answer() throws Throwable
        {
            assertEquals("Wrong current index", expIndex,
                    context.getCurrentSongIndex());
            assertEquals("Wrong time", expTime, context.getPlaybackTime());
            return null;
        }
    }
}
