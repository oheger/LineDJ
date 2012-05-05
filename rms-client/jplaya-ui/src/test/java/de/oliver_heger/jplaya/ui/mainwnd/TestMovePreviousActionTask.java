package de.oliver_heger.jplaya.ui.mainwnd;

import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Test;

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
     * Tests an execution of the task.
     */
    @Test
    public void testRun()
    {
        ctrl.moveBackward();
        replayAll();
        task.run();
        verifyAll();
    }
}
