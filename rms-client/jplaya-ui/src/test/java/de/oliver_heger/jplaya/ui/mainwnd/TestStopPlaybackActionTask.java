package de.oliver_heger.jplaya.ui.mainwnd;

import org.easymock.EasyMock;
import org.junit.Test;

/**
 * Test class for {@code StopPlaybackActionTask}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestStopPlaybackActionTask
{
    /**
     * Tests whether the task can be executed successfully.
     */
    @Test
    public void testRun()
    {
        MainWndController ctrl = EasyMock.createMock(MainWndController.class);
        ctrl.stopPlayback();
        EasyMock.replay(ctrl);
        StopPlaybackActionTask task = new StopPlaybackActionTask(ctrl);
        task.run();
        EasyMock.verify(ctrl);
    }
}
