package de.oliver_heger.jplaya.ui.mainwnd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.sf.jguiraffe.gui.builder.components.model.TableHandler;

import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.jplaya.playlist.PlaylistController;
import de.oliver_heger.jplaya.playlist.PlaylistManager;

/**
 * Test class for {@code MoveToIndexActionTask}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestMoveToIndexActionTask extends EasyMockSupport
{
    /** A mock for the controller. */
    private MainWndController controller;

    /** A mock for the table handler. */
    private TableHandler handler;

    @Before
    public void setUp() throws Exception
    {
        controller = createMock(MainWndController.class);
        handler = createMock(TableHandler.class);
    }

    /**
     * Tries to create an instance without a table handler.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoTabHandler()
    {
        new MoveToIndexActionTask(controller, null);
    }

    /**
     * Tests whether the index in the playlist is correctly updated.
     */
    @Test
    public void testUpdatePlaylistIndex()
    {
        PlaylistController plc = createMock(PlaylistController.class);
        PlaylistManager pm = createMock(PlaylistManager.class);
        final int selIdx = 42;
        EasyMock.expect(controller.getPlaylistController()).andReturn(plc);
        EasyMock.expect(plc.getPlaylistManager()).andReturn(pm);
        EasyMock.expect(handler.getSelectedIndex()).andReturn(selIdx);
        pm.setCurrentSongIndex(selIdx);
        replayAll();
        MoveToIndexActionTask task =
                new MoveToIndexActionTask(controller, handler);
        assertTrue("Wrong result", task.updatePlaylistIndex());
        verifyAll();
    }

    /**
     * Tests updatePlaylistIndex() if there is no selection. (Not sure whether
     * this can actually happen, but we should better be on the safe side.)
     */
    @Test
    public void testUpdatePlaylistIndexNoSelection()
    {
        EasyMock.expect(handler.getSelectedIndex()).andReturn(-1);
        replayAll();
        MoveToIndexActionTask task =
                new MoveToIndexActionTask(controller, handler);
        assertFalse("Wrong result", task.updatePlaylistIndex());
        verifyAll();
    }
}
