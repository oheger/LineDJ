package de.oliver_heger.jplaya.ui.mainwnd;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@code InitPlaylistTask}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestInitPlaylistTask
{
    /** A mock for the main controller. */
    private MainWndController controller;

    @Before
    public void setUp() throws Exception
    {
        controller = EasyMock.createMock(MainWndController.class);
    }

    /**
     * Tries to create an instance without a controller.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoController()
    {
        new InitPlaylistTask(null);
    }

    /**
     * Tests an execution of the task.
     */
    @Test
    public void testRun()
    {
        controller.initPlaylist();
        EasyMock.replay(controller);
        InitPlaylistTask task = new InitPlaylistTask(controller);
        task.run();
        EasyMock.verify(controller);
    }
}
