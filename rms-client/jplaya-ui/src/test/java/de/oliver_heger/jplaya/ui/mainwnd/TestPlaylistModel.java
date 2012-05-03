package de.oliver_heger.jplaya.ui.mainwnd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import net.sf.jguiraffe.gui.builder.components.model.TableHandler;
import net.sf.jguiraffe.gui.forms.Form;

import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.easymock.IAnswer;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.splaya.AudioPlayerEvent;
import de.oliver_heger.splaya.AudioPlayerEventType;
import de.oliver_heger.splaya.AudioSource;
import de.oliver_heger.splaya.PlaylistData;
import de.oliver_heger.splaya.PlaylistEvent;
import de.oliver_heger.splaya.PlaylistEventType;

/**
 * Test class for {@code PlaylistModel}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestPlaylistModel extends EasyMockSupport
{
    /** Constant for the index of a song affected by a test event. */
    private static final int INDEX = 42;

    /** Constant for an audio source used in events. */
    private static final AudioSource SOURCE = new AudioSource("someURI", INDEX,
            0, 0, 0);

    /** A mock for the form. */
    private Form form;

    /** A mock for the table handler. */
    private TableHandler handler;

    /** A mock for the playlist data. */
    private PlaylistData pldata;

    @Before
    public void setUp() throws Exception
    {
        form = createMock(Form.class);
        handler = createMock(TableHandler.class);
        pldata = createMock(PlaylistData.class);
    }

    /**
     * Creates a mock for a player event of the given type.
     *
     * @param type the type
     * @return the mock for the event
     */
    private AudioPlayerEvent createPlayerEvent(AudioPlayerEventType type)
    {
        AudioPlayerEvent event = createMock(AudioPlayerEvent.class);
        EasyMock.expect(event.getType()).andReturn(type).anyTimes();
        EasyMock.expect(event.getSource()).andReturn(SOURCE).anyTimes();
        return event;
    }

    /**
     * Creates a mock for a playlist event of the given type.
     *
     * @param type the event type
     * @return the mock for the event
     */
    private PlaylistEvent createPlaylistEvent(PlaylistEventType type)
    {
        return createPlaylistEvent(type, INDEX);
    }

    /**
     * Creates a mock for a playlist event for the given type and update index.
     *
     * @param type the event type
     * @param idx the index of the updated item
     * @return the mock for the event
     */
    private PlaylistEvent createPlaylistEvent(PlaylistEventType type, int idx)
    {
        PlaylistEvent event = EasyMock.createMock(PlaylistEvent.class);
        EasyMock.expect(event.getType()).andReturn(type).anyTimes();
        EasyMock.expect(event.getPlaylistData()).andReturn(pldata).anyTimes();
        EasyMock.expect(event.getUpdateIndex()).andReturn(idx).anyTimes();
        EasyMock.replay(event);
        return event;
    }

    /**
     * Creates a default test instance.
     *
     * @return the test model
     */
    private PlaylistModel createModel()
    {
        return new PlaylistModel(form, handler);
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
     * Helper method for testing that an audio player event is ignored if there
     * is not yet a playlist.
     *
     * @param type the event type
     */
    private void checkIgnoredPlayerEvent(AudioPlayerEventType type)
    {
        AudioPlayerEvent event = createPlayerEvent(type);
        replayAll();
        PlaylistModel model = createModel();
        model.handleAudioPlayerEvent(event);
        verifyAll();
    }

    /**
     * Tests that a player event is ignored if there is no current item.
     */
    @Test
    public void testPlayerEventNoCurrentItem()
    {
        checkIgnoredPlayerEvent(AudioPlayerEventType.POSITION_CHANGED);
    }

    /**
     * Tests that a start source event is ignored if there is no playlist.
     */
    @Test
    public void testSourceStartEventNoPlaylist()
    {
        checkIgnoredPlayerEvent(AudioPlayerEventType.START_SOURCE);
    }

    /**
     * Tests that a playlist event is ignored if there is no current item.
     */
    @Test
    public void testPlaylistEventNoCurrentItem()
    {
        PlaylistEvent event =
                createPlaylistEvent(PlaylistEventType.PLAYLIST_UPDATED);
        replayAll();
        createModel().handlePlaylistEvent(event);
        verifyAll();
    }

    /**
     * Prepares the mocks to expect an update operation for the form. The passed
     * in bean is checked.
     *
     * @param model the model to be tested
     */
    private void expectUpdateForm(final PlaylistModel model)
    {
        form.initFields(EasyMock.anyObject());
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>()
        {
            @Override
            public Object answer() throws Throwable
            {
                PlaylistItem item = model.getCurrentPlaylistItem();
                assertSame("Wrong form bean", item,
                        EasyMock.getCurrentArguments()[0]);
                assertEquals("Wrong playlist data", pldata,
                        item.getPlaylistData());
                assertEquals("Wrong index", INDEX, item.getIndex());
                return null;
            }
        });
    }

    /**
     * Creates a test model which has already been initialized with a playlist.
     *
     * @return the test model
     */
    private PlaylistModel createModelWithPlaylist()
    {
        PlaylistModel model = createModel();
        model.handlePlaylistEvent(createPlaylistEvent(PlaylistEventType.PLAYLIST_CREATED));
        return model;
    }

    /**
     * Tests whether an event for starting a new audio source is handled
     * correctly.
     */
    @Test
    public void testSourceStartsEvent()
    {
        PlaylistModel model = createModelWithPlaylist();
        expectUpdateForm(model);
        AudioPlayerEvent event =
                createPlayerEvent(AudioPlayerEventType.START_SOURCE);
        handler.setSelectedIndex(INDEX);
        replayAll();
        model.handleAudioPlayerEvent(event);
        PlaylistItem item = model.getCurrentPlaylistItem();
        assertEquals("Wrong position", 0, item.getPlaybackRatio());
        assertEquals("Wrong time", 0, item.getCurrentPlaybackTime());
        verifyAll();
    }

    /**
     * Tests whether an event about a changed playback position is handled
     * correctly.
     */
    @Test
    public void testPositionChangedEvent()
    {
        PlaylistModel model = createModelWithPlaylist();
        expectUpdateForm(model);
        expectUpdateForm(model);
        AudioPlayerEvent event1 =
                createPlayerEvent(AudioPlayerEventType.START_SOURCE);
        AudioPlayerEvent event2 =
                createPlayerEvent(AudioPlayerEventType.POSITION_CHANGED);
        final int relPos = 55;
        final long time = 20120503172600L;
        EasyMock.expect(event2.getRelativePosition()).andReturn(relPos);
        EasyMock.expect(event2.getPlaybackTime()).andReturn(time);
        handler.setSelectedIndex(INDEX);
        replayAll();
        model.handleAudioPlayerEvent(event1);
        model.handleAudioPlayerEvent(event2);
        PlaylistItem item = model.getCurrentPlaylistItem();
        assertEquals("Wrong position", relPos, item.getPlaybackRatio());
        assertEquals("Wrong time", time, item.getCurrentPlaybackTime());
        verifyAll();
    }

    /**
     * Tests whether a playlist update event referencing another item is handled
     * correctly.
     */
    @Test
    public void testPlaylistUpdateEventOtherIndex()
    {
        PlaylistModel model = createModelWithPlaylist();
        expectUpdateForm(model);
        AudioPlayerEvent pevent =
                createPlayerEvent(AudioPlayerEventType.START_SOURCE);
        handler.setSelectedIndex(INDEX);
        PlaylistEvent event =
                createPlaylistEvent(PlaylistEventType.PLAYLIST_UPDATED,
                        INDEX - 1);
        replayAll();
        model.handleAudioPlayerEvent(pevent);
        model.handlePlaylistEvent(event);
        verifyAll();
    }

    /**
     * Tests whether a playlist update event referencing the current playlist
     * item is handled correctly.
     */
    @Test
    public void testPlaylistUpdateEventCurrentItem()
    {
        PlaylistModel model = createModelWithPlaylist();
        expectUpdateForm(model);
        expectUpdateForm(model);
        AudioPlayerEvent pevent =
                createPlayerEvent(AudioPlayerEventType.START_SOURCE);
        handler.setSelectedIndex(INDEX);
        PlaylistEvent event =
                createPlaylistEvent(PlaylistEventType.PLAYLIST_UPDATED, INDEX);
        replayAll();
        model.handleAudioPlayerEvent(pevent);
        model.handlePlaylistEvent(event);
        verifyAll();
    }
}
