package de.oliver_heger.jplaya.ui.mainwnd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import net.sf.jguiraffe.gui.builder.components.model.TableHandler;
import net.sf.jguiraffe.gui.forms.Form;

import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.mutable.MutableObject;
import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.easymock.IAnswer;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.service.SongData;
import de.olix.playa.engine.mediainfo.SongDataEvent;
import de.olix.playa.engine.mediainfo.SongDataManager;
import de.olix.playa.playlist.PlaylistInfo;

/**
 * Test class for {@code PlaylistModel}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestPlaylistModel extends EasyMockSupport
{
    /** Constant for the index of a song affected by a test event. */
    private static final Integer INDEX = 42;

    /** Constant for the URI prefix for test songs. */
    private static final String URI = "TestSong_";

    /** Constant for the number of test songs. */
    private static final int COUNT = 128;

    /** A mock for the form. */
    private Form form;

    /** A mock for the table handler. */
    private TableHandler handler;

    /** The collection with the data of the table model. */
    private List<PlaylistItem> modelData;

    @Before
    public void setUp() throws Exception
    {
        form = createMock(Form.class);
        handler = createMock(TableHandler.class);
        modelData = new ArrayList<PlaylistItem>();
        handler.getModel();
        EasyMock.expectLastCall().andReturn(modelData).anyTimes();
    }

    /**
     * Creates a list with test URIs for the playlist items.
     *
     * @return the list with URIs
     */
    private static List<String> createPlayistURIs()
    {
        List<String> uris = new ArrayList<String>(COUNT);
        for (int i = 0; i < COUNT; i++)
        {
            uris.add(URI + i);
        }
        return uris;
    }

    /**
     * Initializes the test model with items.
     *
     * @param model the model
     * @param sdm the song data manager
     * @param initHandler a flag whether the table handler mock should be
     *        initialized
     * @return the list with items
     */
    private List<PlaylistItem> initItems(PlaylistModel model,
            SongDataManager sdm)
    {
        List<String> uris = createPlayistURIs();
        List<PlaylistItem> items = model.createModelItems(uris);
        model.initialize(sdm, items);
        return items;
    }

    /**
     * Tries to create an instance without a form.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoForm()
    {
        new PlaylistModel(null, handler);
    }

    /**
     * Tries to create an instance without a handler.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoTabHandler()
    {
        new PlaylistModel(form, null);
    }

    /**
     * Tests whether the context of a newly created instance contains default
     * values.
     */
    @Test
    public void testInitDefaultContextValues()
    {
        PlaylistModel model = new PlaylistModel(form, handler);
        PlaylistContext ctx = model.getPlaylistContext();
        assertTrue("Got a valid song index", ctx.getCurrentSongIndex() < 0);
        PlaylistInfo info = ctx.getPlaylistInfo();
        assertEquals("Wrong name", "", info.getName());
        assertEquals("Wrong description", "", info.getDescription());
        assertEquals("Wrong number of songs", 0, info.getNumberOfSongs());
    }

    /**
     * Tests whether the data model can be initialized.
     */
    @Test
    public void testInitialize()
    {
        SongDataManager sdm = createMock(SongDataManager.class);
        handler.tableDataChanged();
        replayAll();
        modelData.add(null);
        PlaylistModel model = new PlaylistModel(form, handler);
        List<PlaylistItem> items = initItems(model, sdm);
        assertEquals("Wrong number of items", COUNT, modelData.size());
        for (int i = 0; i < COUNT; i++)
        {
            assertEquals("Wrong item at " + i, items.get(i), modelData.get(i));
        }
        assertSame("Wrong current manager", sdm,
                model.getCurrentSongDataManager());
        verifyAll();
    }

    /**
     * Tests whether item objects can be created.
     */
    @Test
    public void testCreateModelItems()
    {
        PlaylistModel model = new PlaylistModel(form, handler);
        List<PlaylistItem> items = model.createModelItems(createPlayistURIs());
        assertEquals("Wrong number of items", COUNT, items.size());
        assertNotNull("No context", model.getPlaylistContext());
        int idx = 0;
        for (PlaylistItem pi : items)
        {
            assertSame("Wrong context", model.getPlaylistContext(),
                    pi.getPlaylistContext());
            assertEquals("Wrong index", idx, pi.getIndex());
            assertEquals("Wrong URI", URI + idx, pi.getUri());
            assertNotNull("No song data", pi.getSongData());
            idx++;
        }
    }

    /**
     * Tests whether a song data event can be processed that is not related to
     * the current song.
     */
    @Test
    public void testHandleEventNotCurrentSong()
    {
        SongDataManager sdm = createMock(SongDataManager.class);
        SongDataEvent event = createMock(SongDataEvent.class);
        SongData data = createMock(SongData.class);
        EasyMock.expect(event.getMediaFileID()).andReturn(INDEX).anyTimes();
        EasyMock.expect(sdm.getDataForID(INDEX)).andReturn(data);
        handler.tableDataChanged();
        handler.rowsUpdated(INDEX.intValue(), INDEX.intValue());
        replayAll();
        PlaylistModel model = new PlaylistModel(form, handler);
        List<PlaylistItem> items = initItems(model, sdm);
        model.handleEvent(event);
        assertSame("Data not set", data, items.get(INDEX.intValue())
                .getSongData());
        verifyAll();
    }

    /**
     * Tests whether a song data event can be processed that also effects the
     * current song.
     */
    @Test
    public void testHandleEventCurrentSong()
    {
        SongDataManager sdm = createMock(SongDataManager.class);
        SongDataEvent event = createMock(SongDataEvent.class);
        SongData data = createMock(SongData.class);
        EasyMock.expect(event.getMediaFileID()).andReturn(INDEX).anyTimes();
        EasyMock.expect(sdm.getDataForID(INDEX)).andReturn(data);
        handler.tableDataChanged();
        handler.rowsUpdated(INDEX.intValue(), INDEX.intValue());
        replayAll();
        final MutableInt updateFormCounter = new MutableInt();
        PlaylistModel model = new PlaylistModel(form, handler)
        {
            @Override
            void updateForm()
            {
                updateFormCounter.increment();
            }
        };
        initItems(model, sdm);
        model.getPlaylistContext().setCurrentSongIndex(INDEX);
        model.handleEvent(event);
        assertEquals("Wrong update count", 1, updateFormCounter.getValue()
                .intValue());
        verifyAll();
    }

    /**
     * Tests whether the UI can be updated for the current song.
     */
    @Test
    public void testUpdateForm()
    {
        SongDataManager sdm = createMock(SongDataManager.class);
        handler.tableDataChanged();
        handler.setSelectedIndex(INDEX);
        replayAll();
        PlaylistModel model = new PlaylistModel(form, handler);
        List<PlaylistItem> items = initItems(model, sdm);
        EasyMock.reset(form);
        form.initFields(items.get(INDEX.intValue()));
        EasyMock.replay(form);
        model.getPlaylistContext().setCurrentSongIndex(INDEX);
        model.updateForm();
        verifyAll();
    }

    /**
     * Tests multiple updateForm() invocations with the same current song index.
     * In this case the table index should not be changed.
     */
    @Test
    public void testUpdateFormNoIndexChange()
    {
        SongDataManager sdm = createMock(SongDataManager.class);
        handler.tableDataChanged();
        handler.setSelectedIndex(INDEX);
        replayAll();
        PlaylistModel model = new PlaylistModel(form, handler);
        List<PlaylistItem> items = initItems(model, sdm);
        EasyMock.reset(form);
        form.initFields(items.get(INDEX.intValue()));
        EasyMock.expectLastCall().times(2);
        EasyMock.replay(form);
        model.getPlaylistContext().setCurrentSongIndex(INDEX);
        model.updateForm();
        model.updateForm();
        verifyAll();
    }

    /**
     * Tests whether the content of the form can be reset if an invalid index is
     * provided.
     */
    @Test
    public void testUpdateFormReset()
    {
        SongDataManager sdm = createMock(SongDataManager.class);
        handler.tableDataChanged();
        final MutableObject<Object> formBean = new MutableObject<Object>();
        form.initFields(EasyMock.anyObject());
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>()
        {
            @Override
            public Object answer() throws Throwable
            {
                formBean.setValue(EasyMock.getCurrentArguments()[0]);
                return null;
            }
        });
        replayAll();
        PlaylistModel model = new PlaylistModel(form, handler);
        initItems(model, sdm);
        model.getPlaylistContext().setCurrentSongIndex(-1);
        model.updateForm();
        verifyAll();
        PlaylistItem item = (PlaylistItem) formBean.getValue();
        assertEquals("Got a valid index", -1, item.getIndex());
        assertNull("Got a URI", item.getUri());
        assertEquals("Wrong playlist name", "", item.getPlaylistName());
    }

    /**
     * Helper method for creating a mock event.
     *
     * @param sdm the song data manager
     * @return the mock event
     */
    private SongDataEvent createEvent(SongDataManager sdm)
    {
        SongDataEvent event = createMock(SongDataEvent.class);
        EasyMock.expect(event.getManager()).andReturn(sdm).anyTimes();
        return event;
    }

    /**
     * Tests whether a song data event can be processed if the playlist is fully
     * initialized.
     */
    @Test
    public void testProcessSongDataEventInitialized()
    {
        SongDataManager sdm = createMock(SongDataManager.class);
        SongDataEvent event = createEvent(sdm);
        handler.tableDataChanged();
        replayAll();
        PlaylistModelTestImpl model = new PlaylistModelTestImpl(form, handler);
        initItems(model, sdm);
        model.verifyAllEvents();
        model.verifyUpdateForm(0);
        model.processSongDataEvent(event);
        model.verifyEvents(event);
        model.verifyAllEvents();
        model.verifyUpdateForm(0);
        verifyAll();
    }

    /**
     * Tests whether the UI can be updated if the playlist is fully initialized.
     */
    @Test
    public void testUpdateUIInitialized()
    {
        SongDataManager sdm = createMock(SongDataManager.class);
        handler.tableDataChanged();
        replayAll();
        PlaylistModelTestImpl model = new PlaylistModelTestImpl(form, handler);
        initItems(model, sdm);
        model.updateUI(sdm);
        model.verifyUpdateForm(1);
        verifyAll();
    }

    /**
     * Tests whether operations before the initialization of the playlist are
     * recorded and are processed later.
     */
    @Test
    public void testEventsBeforeInitialize()
    {
        SongDataManager sdm = createMock(SongDataManager.class);
        SongDataEvent ev1 = createEvent(sdm);
        SongDataEvent ev2 = createEvent(sdm);
        handler.tableDataChanged();
        replayAll();
        PlaylistModelTestImpl model = new PlaylistModelTestImpl(form, handler);
        model.processSongDataEvent(ev1);
        model.getPlaylistContext().setCurrentSongIndex(5);
        model.updateUI(sdm);
        model.processSongDataEvent(ev2);
        model.verifyUpdateForm(0);
        model.verifyAllEvents();
        initItems(model, sdm);
        model.verifyEvents(ev1, ev2);
        model.verifyAllEvents();
        model.verifyUpdateForm(1);
        verifyAll();
    }

    /**
     * Tests whether events can be processed separately from form updates before
     * the playlist is fully initialized.
     */
    @Test
    public void testEventsButNoFormUpdateBeforeInitialize()
    {
        SongDataManager sdm = createMock(SongDataManager.class);
        SongDataEvent event = createEvent(sdm);
        handler.tableDataChanged();
        replayAll();
        PlaylistModelTestImpl model = new PlaylistModelTestImpl(form, handler);
        model.processSongDataEvent(event);
        model.getPlaylistContext().setCurrentSongIndex(5);
        initItems(model, sdm);
        model.verifyEvents(event);
        model.verifyAllEvents();
        model.verifyUpdateForm(0);
        verifyAll();
    }

    /**
     * A test implementation of the model with some mocking facilities.
     */
    private static class PlaylistModelTestImpl extends PlaylistModel
    {
        /** A list with events that have been handled. */
        private final List<SongDataEvent> events;

        /** A counter for invocations of updateForm(). */
        private int updateFormCount;

        public PlaylistModelTestImpl(Form frm, TableHandler tab)
        {
            super(frm, tab);
            events = new LinkedList<SongDataEvent>();
        }

        /**
         * Checks whether the specified events have been received in this order.
         *
         * @param events the expected events
         */
        public void verifyEvents(SongDataEvent... expevents)
        {
            for (SongDataEvent ev : expevents)
            {
                assertFalse("Too few events", events.isEmpty());
                assertSame("Wrong event", ev, events.remove(0));
            }
        }

        /**
         * Checks whether all events have been processed.
         */
        public void verifyAllEvents()
        {
            assertTrue("Too many events: " + events, events.isEmpty());
        }

        /**
         * Checks whether the expected number of update form invocations
         * occurred.
         *
         * @param expCount the expected count
         */
        public void verifyUpdateForm(int expCount)
        {
            assertEquals("Wrong number of update form calls", expCount,
                    updateFormCount);
        }

        /**
         * Records this invocation.
         */
        @Override
        void handleEvent(SongDataEvent event)
        {
            events.add(event);
        }

        /**
         * Records this invocation.
         */
        @Override
        void updateForm()
        {
            updateFormCount++;
        }
    }
}
