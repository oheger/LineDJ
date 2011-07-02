package de.oliver_heger.jplaya.ui.mainwnd;

import org.easymock.EasyMock;
import org.junit.Test;

/**
 * Test class for {@code StartPlaybackActionTask}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestStartPlaybackActionTask
{
    /**
     * Tries to create an instance without a controller.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoController()
    {
        new StartPlaybackActionTask(null);
    }

    /**
     * Tests whether the action can be executed successfully.
     */
    @Test
    public void testRun()
    {
        MainWndController ctrl = EasyMock.createMock(MainWndController.class);
        ctrl.startPlayback();
        EasyMock.replay(ctrl);
        StartPlaybackActionTask task = new StartPlaybackActionTask(ctrl);
        task.run();
        EasyMock.verify(ctrl);
    }
}
