package de.oliver_heger.jplaya.ui.mainwnd;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@code PlayerShutdownActionTask}.
 *
 * @author hacker
 * @version $Id: $
 */
public class TestPlayerShutdownActionTask
{
    /** A mock for the controller. */
    private MainWndController ctrl;

    /** The task to be tested. */
    private PlayerShutdownActionTask task;

    @Before
    public void setUp() throws Exception
    {
        ctrl = EasyMock.createMock(MainWndController.class);
        task = new PlayerShutdownActionTask(ctrl);
    }

    /**
     * Tests the execution of the task.
     */
    @Test
    public void testExecution()
    {
        ctrl.shutdown();
        EasyMock.replay(ctrl);
        task.run();
        EasyMock.verify(ctrl);
    }
}
