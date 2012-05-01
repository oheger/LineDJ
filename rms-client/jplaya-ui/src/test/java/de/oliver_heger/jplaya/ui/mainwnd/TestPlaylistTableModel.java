package de.oliver_heger.jplaya.ui.mainwnd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;

import net.sf.jguiraffe.gui.builder.components.model.TableHandler;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.splaya.PlaylistData;
import de.oliver_heger.splaya.PlaylistEvent;
import de.oliver_heger.splaya.PlaylistEventType;

/**
 * Test class for {@code PlaylistTableModel}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestPlaylistTableModel
{
    /** Constant for the size of the playlist. */
    private static final int PLAYLIST_SIZE = 128;

    /** The table handler mock. */
    private TableHandler tabHandler;

    /** The list acting as data model. */
    private List<Object> dataList;

    /** The model to be tested. */
    private PlaylistTableModel model;

    @Before
    public void setUp() throws Exception
    {
        tabHandler = EasyMock.createMock(TableHandler.class);
        model = new PlaylistTableModel(tabHandler);
        dataList = new ArrayList<Object>();
        EasyMock.expect(tabHandler.getModel()).andReturn(dataList).anyTimes();
    }

    /**
     * Helper method for creating a playlist event.
     *
     * @param type the event type
     * @param idx the update index
     * @return the event (actually a mock)
     */
    private static PlaylistEvent createEvent(PlaylistEventType type, int idx)
    {
        PlaylistEvent event = EasyMock.createMock(PlaylistEvent.class);
        PlaylistData pldata = EasyMock.createMock(PlaylistData.class);
        EasyMock.expect(event.getPlaylistData()).andReturn(pldata).anyTimes();
        EasyMock.expect(event.getType()).andReturn(type).anyTimes();
        EasyMock.expect(event.getUpdateIndex()).andReturn(idx).anyTimes();
        EasyMock.expect(pldata.size()).andReturn(PLAYLIST_SIZE).anyTimes();
        EasyMock.replay(event, pldata);
        return event;
    }

    /**
     * Tests whether an event for a newly created playlist is handled correctly.
     */
    @Test
    public void testNewPlaylistEvent()
    {
        tabHandler.tableDataChanged();
        EasyMock.replay(tabHandler);
        PlaylistEvent event =
                createEvent(PlaylistEventType.PLAYLIST_CREATED, -1);
        model.handlePlaylistEvent(event);
        assertEquals("Wrong number of playlist items", PLAYLIST_SIZE,
                dataList.size());
        int idx = 0;
        for (Object obj : dataList)
        {
            PlaylistTableItem item = (PlaylistTableItem) obj;
            assertSame("Wrong playlist data", event.getPlaylistData(),
                    item.getPlaylistData());
            assertEquals("Wrong index", idx, item.getIndex());
            idx++;
        }
        EasyMock.verify(tabHandler);
    }

    /**
     * Tests whether a playlist update event is handled correctly.
     */
    @Test
    public void testPlaylistUpdateEvent()
    {
        final int idx = 111;
        tabHandler.rowsUpdated(idx, idx);
        EasyMock.replay(tabHandler);
        model.handlePlaylistEvent(createEvent(
                PlaylistEventType.PLAYLIST_UPDATED, idx));
        EasyMock.verify(tabHandler);
    }
}
