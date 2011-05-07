package de.oliver_heger.jplaya.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.sf.jguiraffe.di.BeanContext;
import net.sf.jguiraffe.gui.app.ApplicationContext;
import net.sf.jguiraffe.gui.builder.action.FormAction;
import net.sf.jguiraffe.gui.builder.components.ComponentBuilderData;
import net.sf.jguiraffe.gui.builder.components.model.ProgressBarHandler;
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler;
import net.sf.jguiraffe.gui.builder.event.FormActionEvent;
import net.sf.jguiraffe.gui.builder.utils.GUIRuntimeException;
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer;
import net.sf.jguiraffe.gui.builder.window.Window;
import net.sf.jguiraffe.gui.builder.window.WindowEvent;
import net.sf.jguiraffe.gui.forms.ComponentHandler;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import de.oliver_heger.mediastore.localstore.MediaStore;
import de.oliver_heger.mediastore.localstore.model.SongEntity;
import de.oliver_heger.mediastore.oauth.OAuthCallback;
import de.oliver_heger.mediastore.service.ObjectFactory;
import de.oliver_heger.mediastore.service.SongData;

/**
 * Test class for {@code SyncControllerImpl}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSyncControllerImpl
{
    /** Constant for the maximum number of songs to synchronize. */
    private static final Integer MAX_SONGS = 50;

    /** Constant for the prefix for the errors label. */
    private static final String LAB_ERRORS = "Errors: ";

    /** Constant for the format pattern for errors. */
    private static final String FMT_ERRORS = LAB_ERRORS + "%d";

    /** Constant for the prefix for the new objects label. */
    private static final String LAB_NEWOBJECTS = "New objects: ";

    /** Constant for the format pattern for new objects. */
    private static final String FMT_NEWOBJECTS = LAB_NEWOBJECTS + "%d %d %d";

    /** Constant for the prefix for the status label. */
    private static final String LAB_STATUS = "Synchronizing ";

    /** Constant for the format pattern for the status label. */
    private static final String FMT_STATUS = LAB_STATUS + "%s";

    /** Constant for a song name. */
    private static final String SONG_NAME = "Shaddow on the wall";

    /** Constant for the finished status text. */
    private static final String FINISHED = "Done!";

    /** Constant for the step size for the progress bar. */
    private static final int STEP = 2;

    /** A factory for creating data objects. */
    private static ObjectFactory factory;

    /** A mock for the media store service. */
    private MediaStore store;

    /** A mock for the OAuth callback. */
    private OAuthCallback callback;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception
    {
        factory = new ObjectFactory();
    }

    @Before
    public void setUp() throws Exception
    {
        store = EasyMock.createMock(MediaStore.class);
        callback = EasyMock.createMock(OAuthCallback.class);
    }

    /**
     * Tries to create an instance without a media store.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoStore()
    {
        new SyncControllerImpl(null, callback, MAX_SONGS);
    }

    /**
     * Tries to create an instance without a callback.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoCallback()
    {
        new SyncControllerImpl(store, null, MAX_SONGS);
    }

    /**
     * Tests default values of some of the properties of a newly created
     * instance.
     */
    @Test
    public void testInit()
    {
        SyncControllerImpl ctrl =
                new SyncControllerImpl(store, callback, MAX_SONGS);
        assertEquals("Wrong number of errors", 0, ctrl.getErrorCount());
        assertEquals("Wrong number of created artists", 0,
                ctrl.getCreatedArtistsCount());
        assertEquals("Wrong number of created albums", 0,
                ctrl.getCreatedAlbumsCount());
        assertEquals("Wrong number of created songs", 0,
                ctrl.getCreatedAlbumsCount());
        assertNull("Got a window", ctrl.getWindow());
        assertFalse("Canceled", ctrl.isCanceled());
    }

    /**
     * Tests whether the expected OAuth callback is returned.
     */
    @Test
    public void testGetOAuthCallback()
    {
        SyncControllerImpl ctrl =
                new SyncControllerImpl(store, callback, MAX_SONGS);
        assertSame("Wrong callback", callback, ctrl.getOAuthCallback());
    }

    /**
     * Tests whether the object is correctly initialized when the window is
     * opened.
     */
    @Test
    public void testWindowOpened()
    {
        BeanContext bc = EasyMock.createMock(BeanContext.class);
        ApplicationContext appCtx =
                EasyMock.createMock(ApplicationContext.class);
        Window window = EasyMock.createMock(Window.class);
        EasyMock.expect(bc.getBean("jguiraffe.applicationContext")).andReturn(appCtx);
        EasyMock.expect(
                appCtx.getResourceText(SyncControllerImpl.RES_FMT_ERRORS))
                .andReturn(FMT_ERRORS);
        EasyMock.expect(
                appCtx.getResourceText(SyncControllerImpl.RES_FMT_NEWOBJECTS))
                .andReturn(FMT_NEWOBJECTS);
        EasyMock.expect(
                appCtx.getResourceText(SyncControllerImpl.RES_FMT_STATUS))
                .andReturn(FMT_STATUS);
        EasyMock.expect(
                appCtx.getResourceText(SyncControllerImpl.RES_STATUS_FINISHED))
                .andReturn(FINISHED);
        SyncControllerImpl ctrl =
                new SyncControllerImpl(store, callback, MAX_SONGS);
        store.syncWithServer(ctrl, ctrl, MAX_SONGS);
        EasyMock.replay(bc, appCtx, window, store, callback);
        ctrl.setComponentBuilderData(new ComponentBuilderDataTestImpl(null, bc));
        WindowEvent event =
                new WindowEvent(this, window, WindowEvent.Type.WINDOW_OPENED);
        ctrl.windowOpened(event);
        assertSame("Wrong window", window, ctrl.getWindow());
        assertEquals("Wrong error pattern", FMT_ERRORS,
                ctrl.getFmtPatternErrors());
        assertEquals("Wrong new objects pattern", FMT_NEWOBJECTS,
                ctrl.getFmtPatternNewObjects());
        assertEquals("Wrong status pattern", FMT_STATUS,
                ctrl.getFmtPatternStatus());
        assertEquals("Wrong finished status", FINISHED,
                ctrl.getStatusFinished());
        EasyMock.verify(bc, appCtx, window, store, callback);
    }

    /**
     * Tests whether the notification for the start of the synchronization is
     * correctly processed.
     */
    @Test
    public void testStartSynchronization()
    {
        SyncControllerImpl ctrl =
                new SyncControllerImpl(store, callback, MAX_SONGS);
        ctrl.startSynchronization(MAX_SONGS.intValue());
        assertEquals("Wrong number of songs", MAX_SONGS.intValue(),
                ctrl.getSongCount());
    }

    /**
     * Tests beforeSyncSong() if the operation has already been canceled.
     */
    @Test
    public void testBeforeSyncSongCanceled()
    {
        SyncControllerImpl ctrl =
                new SyncControllerImpl(store, callback, MAX_SONGS);
        EasyMock.replay(store, callback);
        GUISynchronizerTestImpl sync = new GUISynchronizerTestImpl();
        ctrl.setSynchronizer(sync);
        ctrl.setCanceled(true);
        assertFalse("Wrong result",
                ctrl.beforeSongSync(factory.createSongData()));
        sync.execute(0);
    }

    /**
     * Tests beforeSyncSong() if the sync operation is in progress.
     */
    @Test
    public void testBeforeSyncSongNotCanceled()
    {
        StaticTextHandler handler =
                EasyMock.createMock(StaticTextHandler.class);
        handler.setText(LAB_STATUS + SONG_NAME);
        EasyMock.replay(handler, store, callback);
        GUISynchronizerTestImpl sync = new GUISynchronizerTestImpl();
        Map<String, ComponentHandler<?>> handlers =
                new HashMap<String, ComponentHandler<?>>();
        handlers.put(SyncControllerImpl.LAB_STATUS, handler);
        ComponentBuilderDataTestImpl cbd =
                new ComponentBuilderDataTestImpl(handlers, null);
        SyncControllerTestImpl ctrl =
                new SyncControllerTestImpl(store, callback, MAX_SONGS);
        ctrl.setSynchronizer(sync);
        ctrl.setComponentBuilderData(cbd);
        SongData data = factory.createSongData();
        data.setName(SONG_NAME);
        assertTrue("Wrong result", ctrl.beforeSongSync(data));
        sync.execute(1);
        EasyMock.verify(handler, store, callback);
    }

    /**
     * Tests whether the UI is correctly updated after a song has been
     * synchronized.
     */
    @Test
    public void testAfterSongSync()
    {
        StaticTextHandler thStatus =
                EasyMock.createMock(StaticTextHandler.class);
        StaticTextHandler thNews = EasyMock.createMock(StaticTextHandler.class);
        ProgressBarHandler progr =
                EasyMock.createMock(ProgressBarHandler.class);
        thStatus.setText("");
        EasyMock.expectLastCall().times(3);
        thNews.setText(LAB_NEWOBJECTS + "1 1 1");
        thNews.setText(LAB_NEWOBJECTS + "2 1 2");
        thNews.setText(LAB_NEWOBJECTS + "2 1 2");
        progr.setValue(STEP);
        progr.setValue(2 * STEP);
        progr.setValue(3 * STEP);
        EasyMock.replay(thStatus, thNews, progr, store, callback);
        GUISynchronizerTestImpl sync = new GUISynchronizerTestImpl();
        Map<String, ComponentHandler<?>> handlers =
                new HashMap<String, ComponentHandler<?>>();
        handlers.put(SyncControllerImpl.LAB_STATUS, thStatus);
        handlers.put(SyncControllerImpl.LAB_NEWOBJECTS, thNews);
        ComponentBuilderDataTestImpl cbd =
                new ComponentBuilderDataTestImpl(handlers, null);
        SyncControllerTestImpl ctrl =
                new SyncControllerTestImpl(store, callback, MAX_SONGS);
        ctrl.setSynchronizer(sync);
        ctrl.setComponentBuilderData(cbd);
        ctrl.setProgressBar(progr);
        ctrl.startSynchronization(MAX_SONGS);
        SongData data = factory.createSongData();
        ctrl.afterSongSync(data, true, true, true);
        ctrl.afterSongSync(data, true, false, true);
        ctrl.afterSongSync(data, false, false, false);
        sync.execute(3);
        assertEquals("Wrong number of synchronized songs", 3,
                ctrl.getSyncCount());
        EasyMock.verify(thStatus, thNews, progr, store, callback);
    }

    /**
     * Tests a failed synchronization of a song.
     */
    @Test
    public void testFailedSongSync()
    {
        StaticTextHandler thErrors =
                EasyMock.createMock(StaticTextHandler.class);
        StaticTextHandler thStatus =
                EasyMock.createMock(StaticTextHandler.class);
        ProgressBarHandler progr =
                EasyMock.createMock(ProgressBarHandler.class);
        thStatus.setText("");
        EasyMock.expectLastCall().times(2);
        thErrors.setText(LAB_ERRORS + "1");
        thErrors.setText(LAB_ERRORS + "2");
        progr.setValue(STEP);
        progr.setValue(2 * STEP);
        EasyMock.replay(thStatus, thErrors, progr, store, callback);
        GUISynchronizerTestImpl sync = new GUISynchronizerTestImpl();
        Map<String, ComponentHandler<?>> handlers =
                new HashMap<String, ComponentHandler<?>>();
        handlers.put(SyncControllerImpl.LAB_STATUS, thStatus);
        handlers.put(SyncControllerImpl.LAB_ERRORS, thErrors);
        ComponentBuilderDataTestImpl cbd =
                new ComponentBuilderDataTestImpl(handlers, null);
        SyncControllerTestImpl ctrl =
                new SyncControllerTestImpl(store, callback, MAX_SONGS);
        ctrl.setSynchronizer(sync);
        ctrl.setComponentBuilderData(cbd);
        ctrl.setProgressBar(progr);
        ctrl.startSynchronization(MAX_SONGS);
        SongData data = factory.createSongData();
        ctrl.failedSongSync(data, new RuntimeException("TestEx1"));
        ctrl.failedSongSync(data, new RuntimeException("TestEx2"));
        sync.execute(2);
        assertEquals("Wrong number of synchronized songs", 2,
                ctrl.getSyncCount());
        assertEquals("Wrong number of errors", 2, ctrl.getErrorCount());
        EasyMock.verify(thStatus, thErrors, progr, store, callback);
    }

    /**
     * Tests whether a failed OAuth authorization is correctly handled.
     */
    @Test
    public void testAuthorizationFailed()
    {
        SongData data = factory.createSongData();
        SyncControllerImpl ctrl =
                new SyncControllerImpl(store, callback, MAX_SONGS);
        ctrl.authorizationFailed(data);
        assertTrue("Not canceled", ctrl.isCanceled());
    }

    /**
     * Tests whether the notification for the end of the synchronization is
     * processed correctly.
     */
    @Test
    public void testEndSynchronization()
    {
        ComponentHandler<?> chCancel =
                EasyMock.createMock(ComponentHandler.class);
        ComponentHandler<?> chClose =
                EasyMock.createMock(ComponentHandler.class);
        StaticTextHandler thStatus =
                EasyMock.createMock(StaticTextHandler.class);
        FormAction act = EasyMock.createMock(FormAction.class);
        chCancel.setEnabled(false);
        chClose.setEnabled(true);
        thStatus.setText(FINISHED);
        act.setEnabled(true);
        EasyMock.replay(chCancel, chClose, thStatus, act, store, callback);
        GUISynchronizerTestImpl sync = new GUISynchronizerTestImpl();
        Map<String, ComponentHandler<?>> handlers =
                new HashMap<String, ComponentHandler<?>>();
        handlers.put(SyncControllerImpl.BTN_CANCEL, chCancel);
        handlers.put(SyncControllerImpl.BTN_CLOSE, chClose);
        handlers.put(SyncControllerImpl.LAB_STATUS, thStatus);
        ComponentBuilderDataTestImpl cbd =
                new ComponentBuilderDataTestImpl(handlers, null);
        SyncControllerTestImpl ctrl =
                new SyncControllerTestImpl(store, callback, MAX_SONGS);
        ctrl.setSynchronizer(sync);
        ctrl.setComponentBuilderData(cbd);
        ctrl.endSynchronization();
        ctrl.setSyncAction(act);
        sync.execute(1);
        EasyMock.verify(chCancel, chClose, thStatus, act, store, callback);
    }

    /**
     * Tests whether the controller reacts correctly on the cancel button.
     */
    @Test
    public void testActionPerformedCancel()
    {
        ComponentHandler<?> chCancel =
                EasyMock.createMock(ComponentHandler.class);
        chCancel.setEnabled(false);
        EasyMock.replay(chCancel, store, callback);
        FormActionEvent event =
                new FormActionEvent(this, chCancel,
                        SyncControllerImpl.BTN_CANCEL,
                        SyncControllerImpl.BTN_CANCEL);
        SyncControllerImpl ctrl =
                new SyncControllerImpl(store, callback, MAX_SONGS);
        ctrl.actionPerformed(event);
        assertTrue("Not canceled", ctrl.isCanceled());
        EasyMock.verify(chCancel, store, callback);
    }

    /**
     * Tests whether the controller reacts correctly on the close button.
     */
    @Test
    public void testActionPerformedClose()
    {
        final Window window = EasyMock.createMock(Window.class);
        ComponentHandler<?> handler =
                EasyMock.createMock(ComponentHandler.class);
        EasyMock.expect(window.close(true)).andReturn(Boolean.TRUE);
        EasyMock.replay(window, handler, store, callback);
        FormActionEvent event =
                new FormActionEvent(this, handler,
                        SyncControllerImpl.BTN_CLOSE,
                        SyncControllerImpl.BTN_CLOSE);
        SyncControllerImpl ctrl =
                new SyncControllerImpl(store, callback, MAX_SONGS)
                {
                    @Override
                    Window getWindow()
                    {
                        return window;
                    }
                };
        ctrl.actionPerformed(event);
        EasyMock.verify(window, handler, store, callback);
    }

    /**
     * Tests whether an unknown action is simply ignored.
     */
    @Test
    public void testActionPerformedUnknown()
    {
        ComponentHandler<?> handler =
                EasyMock.createMock(ComponentHandler.class);
        EasyMock.replay(handler, store, callback);
        FormActionEvent event =
                new FormActionEvent(this, handler, "unknown control",
                        "unknown command");
        SyncControllerImpl ctrl =
                new SyncControllerImpl(store, callback, MAX_SONGS);
        ctrl.actionPerformed(event);
        EasyMock.verify(handler, store, callback);
    }

    /**
     * Creates a mock object for the list of song entities.
     *
     * @return the mock list
     */
    private static List<SongEntity> createListMock()
    {
        @SuppressWarnings("unchecked")
        List<SongEntity> list = EasyMock.createMock(List.class);
        EasyMock.replay(list);
        return list;
    }

    /**
     * Tests whether an error of the execution of the sync command is correctly
     * handled.
     */
    @Test
    public void testCommandCompletedUIErr()
    {
        ComponentHandler<?> chCancel =
                EasyMock.createMock(ComponentHandler.class);
        ComponentHandler<?> chClose =
                EasyMock.createMock(ComponentHandler.class);
        StaticTextHandler labStatus =
                EasyMock.createMock(StaticTextHandler.class);
        ApplicationContext appCtx =
                EasyMock.createMock(ApplicationContext.class);
        BeanContext bc = EasyMock.createMock(BeanContext.class);
        EasyMock.expect(bc.getBean("jguiraffe.applicationContext")).andReturn(
                appCtx);
        final String errText = "Error!!";
        EasyMock.expect(
                appCtx.getResourceText(SyncControllerImpl.RES_ERR_STATUS))
                .andReturn(errText);
        labStatus.setText(errText);
        chCancel.setEnabled(false);
        chClose.setEnabled(true);
        EasyMock.replay(chCancel, chClose, labStatus, appCtx, bc, store,
                callback);
        Map<String, ComponentHandler<?>> handlers =
                new HashMap<String, ComponentHandler<?>>();
        handlers.put(SyncControllerImpl.BTN_CANCEL, chCancel);
        handlers.put(SyncControllerImpl.BTN_CLOSE, chClose);
        handlers.put(SyncControllerImpl.LAB_STATUS, labStatus);
        ComponentBuilderData cbd =
                new ComponentBuilderDataTestImpl(handlers, bc);
        SyncControllerImpl ctrl =
                new SyncControllerImpl(store, callback, MAX_SONGS);
        ctrl.setComponentBuilderData(cbd);
        ctrl.commandCompletedUI(createListMock(), new RuntimeException(
                "Test exception"));
        EasyMock.verify(chCancel, chClose, labStatus, appCtx, bc, store,
                callback);
    }

    /**
     * Tests the notification of a completed UI command if everything was
     * successful. We can only check that the mocks are not touched.
     */
    @Test
    public void testCommandCompletedUISuccess()
    {
        EasyMock.replay(store, callback);
        SyncControllerImpl ctrl =
                new SyncControllerImpl(store, callback, MAX_SONGS);
        ctrl.commandCompletedUI(createListMock(), null);
    }

    /**
     * Tests the notification of a completed command in the background thread.
     */
    @Test
    public void testCommandCompletedBackground()
    {
        EasyMock.replay(store, callback);
        SyncControllerImpl ctrl =
                new SyncControllerImpl(store, callback, MAX_SONGS);
        ctrl.commandCompletedBackground(createListMock());
    }

    /**
     * Tests the notification of a failed command execution.
     */
    @Test
    public void testCommandExecutionFailed()
    {
        EasyMock.replay(store, callback);
        SyncControllerImpl ctrl =
                new SyncControllerImpl(store, callback, MAX_SONGS);
        ctrl.commandExecutionFailed(new RuntimeException("Test exception!"));
    }

    /**
     * Tests the implementation of windowActivated().
     */
    @Test
    public void testWindowActivated()
    {
        WindowEvent e = EasyMock.createMock(WindowEvent.class);
        EasyMock.replay(e);
        new SyncControllerImpl(store, callback, MAX_SONGS).windowActivated(e);
    }

    /**
     * Tests the implementation of windowClosing().
     */
    @Test
    public void testWindowClosing()
    {
        WindowEvent e = EasyMock.createMock(WindowEvent.class);
        EasyMock.replay(e);
        new SyncControllerImpl(store, callback, MAX_SONGS).windowClosing(e);
    }

    /**
     * Tests the implementation of windowClosed().
     */
    @Test
    public void testWindowCloseded()
    {
        WindowEvent e = EasyMock.createMock(WindowEvent.class);
        EasyMock.replay(e);
        new SyncControllerImpl(store, callback, MAX_SONGS).windowClosed(e);
    }

    /**
     * Tests the implementation of windowDeactivated().
     */
    @Test
    public void testWindowDeactivated()
    {
        WindowEvent e = EasyMock.createMock(WindowEvent.class);
        EasyMock.replay(e);
        new SyncControllerImpl(store, callback, MAX_SONGS).windowDeactivated(e);
    }

    /**
     * Tests the implementation of windowDeiconified().
     */
    @Test
    public void testWindowDeiconified()
    {
        WindowEvent e = EasyMock.createMock(WindowEvent.class);
        EasyMock.replay(e);
        new SyncControllerImpl(store, callback, MAX_SONGS).windowDeiconified(e);
    }

    /**
     * Tests the implementation of windowIconified().
     */
    @Test
    public void testWindowIconified()
    {
        WindowEvent e = EasyMock.createMock(WindowEvent.class);
        EasyMock.replay(e);
        new SyncControllerImpl(store, callback, MAX_SONGS).windowIconified(e);
    }

    /**
     * A specialized component builder data implementation which provides some
     * mocking facilities.
     */
    private static class ComponentBuilderDataTestImpl extends
            ComponentBuilderData
    {
        /** A map with the component handlers supported by this object. */
        private final Map<String, ComponentHandler<?>> compHandlers;

        /** A mock bean context. */
        private final BeanContext context;

        /**
         * Creates a new instance of {@code ComponentBuilderDataTestImpl}.
         *
         * @param handlers the map with mock handlers
         * @param bc the mock bean context
         */
        public ComponentBuilderDataTestImpl(
                Map<String, ComponentHandler<?>> handlers, BeanContext bc)
        {
            compHandlers = handlers;
            context = bc;
        }

        /**
         * Returns the handler from the internal map with the given name.
         */
        @Override
        public ComponentHandler<?> getComponentHandler(String name)
        {
            return compHandlers.get(name);
        }

        /**
         * Returns the mock bean context.
         */
        @Override
        public BeanContext getBeanContext()
        {
            return context;
        }
    }

    /**
     * A test implementation of the synchronizer. This implementation just
     * records the Runnable objects passed to it. They can be executed in one
     * step in the same thread.
     */
    private static class GUISynchronizerTestImpl implements GUISynchronizer
    {
        /** A list for the objects passed to this object. */
        private final List<Runnable> runnables = new LinkedList<Runnable>();

        /**
         * Executes all tasks that have been passed to this synchronizer.
         *
         * @param expCount the expected number of tasks
         */
        public void execute(int expCount)
        {
            assertEquals("Wrong number of tasks", expCount, runnables.size());
            for (Runnable r : runnables)
            {
                r.run();
            }
        }

        /**
         * Just records this invocation.
         */
        @Override
        public void asyncInvoke(Runnable runnable)
        {
            runnables.add(runnable);
        }

        /**
         * This method should not be called.
         */
        @Override
        public void syncInvoke(Runnable runnable) throws GUIRuntimeException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        /**
         * This method should not be called.
         */
        @Override
        public boolean isEventDispatchThread()
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }
    }

    /**
     * A test implementation with some mocking facilities.
     */
    private static class SyncControllerTestImpl extends SyncControllerImpl
    {
        public SyncControllerTestImpl(MediaStore store, OAuthCallback callback,
                Integer maxSongs)
        {
            super(store, callback, maxSongs);
        }

        /**
         * Returns the test format pattern.
         */
        @Override
        String getFmtPatternErrors()
        {
            return FMT_ERRORS;
        }

        /**
         * Returns the test format pattern.
         */
        @Override
        String getFmtPatternNewObjects()
        {
            return FMT_NEWOBJECTS;
        }

        /**
         * Returns the test format pattern.
         */
        @Override
        String getFmtPatternStatus()
        {
            return FMT_STATUS;
        }

        /**
         * Returns the test string.
         */
        @Override
        String getStatusFinished()
        {
            return FINISHED;
        }
    }
}
