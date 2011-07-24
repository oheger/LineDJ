package de.oliver_heger.jplaya.ui.mainwnd;

import static org.junit.Assert.assertTrue;

import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Test;

import de.olix.playa.playlist.PlaylistController;
import de.olix.playa.playlist.PlaylistManager;

/**
 * Test class for {@code MovePreviousActionTask}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestMovePreviousActionTask extends EasyMockSupport
{
    /** A mock for the controller. */
    private MainWndController ctrl;

    /** The task to be tested. */
    private MovePreviousActionTask task;

    @Before
    public void setUp() throws Exception
    {
        ctrl = createMock(MainWndController.class);
        task = new MovePreviousActionTask(ctrl);
    }

    /**
     * Tests whether the index of the playlist is updated correctly.
     */
    @Test
    public void testUpdatePlaylistIndex()
    {
        PlaylistController plc = createMock(PlaylistController.class);
        PlaylistManager pm = createMock(PlaylistManager.class);
        EasyMock.expect(ctrl.getPlaylistController()).andReturn(plc);
        EasyMock.expect(plc.getPlaylistManager()).andReturn(pm);
        EasyMock.expect(pm.previousSong()).andReturn(Boolean.TRUE);
        replayAll();
        assertTrue("Wrong result", task.updatePlaylistIndex());
        verifyAll();
    }
}
