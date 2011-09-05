package de.oliver_heger.jplaya.ui.mainwnd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.jplaya.playlist.PlaylistController;
import de.oliver_heger.jplaya.playlist.PlaylistManager;

/**
 * Test class for {@code RecoverFromErrorActionTask}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestRecoverFromErrorActionTask
{
    /** A mock for the controller. */
    private MainWndController controller;

    /** The task to be tested. */
    private RecoverFromErrorActionTask task;

    @Before
    public void setUp() throws Exception
    {
        controller = EasyMock.createMock(MainWndController.class);
        task = new RecoverFromErrorActionTask(controller);
    }

    /**
     * Tests updatePlaylistIndex() if the index could be changed successfully.
     */
    @Test
    public void testUpdatePlaylistIndexSuccess()
    {
        PlaylistController plc = EasyMock.createMock(PlaylistController.class);
        PlaylistManager pm = EasyMock.createMock(PlaylistManager.class);
        EasyMock.expect(controller.getPlaylistController()).andReturn(plc);
        EasyMock.expect(plc.getPlaylistManager()).andReturn(pm);
        EasyMock.expect(pm.nextSong()).andReturn(Boolean.TRUE);
        EasyMock.replay(controller, plc, pm);
        assertTrue("Wrong result", task.updatePlaylistIndex());
        EasyMock.verify(controller, plc, pm);
    }

    /**
     * Tests updatePlaylistIndex() if the end of the playlist is already
     * reached.
     */
    @Test
    public void testUpdatePlaylistIndexEndOfList()
    {
        PlaylistController plc = EasyMock.createMock(PlaylistController.class);
        PlaylistManager pm = EasyMock.createMock(PlaylistManager.class);
        EasyMock.expect(controller.getPlaylistController()).andReturn(plc);
        EasyMock.expect(plc.getPlaylistManager()).andReturn(pm);
        EasyMock.expect(pm.nextSong()).andReturn(Boolean.FALSE);
        controller.playListEnds(null);
        EasyMock.replay(controller, plc, pm);
        assertFalse("Wrong result", task.updatePlaylistIndex());
        EasyMock.verify(controller, plc, pm);
    }
}
