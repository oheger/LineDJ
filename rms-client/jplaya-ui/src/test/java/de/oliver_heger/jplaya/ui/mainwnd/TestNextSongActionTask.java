package de.oliver_heger.jplaya.ui.mainwnd;

import org.easymock.EasyMock;
import org.junit.Test;

/**
 * Test class for {@code NextSongActionTask}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestNextSongActionTask
{
    /**
     * Tests whether the task can be executed successfully.
     */
    @Test
    public void testRun()
    {
        MainWndController ctrl = EasyMock.createMock(MainWndController.class);
        ctrl.skipToNextSong();
        EasyMock.replay(ctrl);
        NextSongActionTask task = new NextSongActionTask(ctrl);
        task.run();
        EasyMock.verify(ctrl);
    }
}
