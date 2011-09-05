package de.oliver_heger.jplaya.ui.mainwnd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import net.sf.jguiraffe.gui.app.Application;

import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@code AbstractChangeCurrentSongActionTask}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAbstractChangeCurrentSongActionTask extends EasyMockSupport
{
    /** A mock for the controller. */
    private MainWndController ctrl;

    @Before
    public void setUp() throws Exception
    {
        ctrl = createMock(MainWndController.class);
    }

    /**
     * Tries to create an instance without a controller.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoCtrl()
    {
        new ChangeCurrentSongActionTaskTestImpl(null, true);
    }

    /**
     * Tests whether an instance can be correctly initialized.
     */
    @Test
    public void testInit()
    {
        replayAll();
        ChangeCurrentSongActionTaskTestImpl task =
                new ChangeCurrentSongActionTaskTestImpl(ctrl, true);
        assertSame("Wrong controller", ctrl, task.getController());
        assertFalse("GUI updates", task.isUpdateGUI());
    }

    /**
     * Tests the execution of the action task if the playlist could be updated.
     */
    @Test
    public void testRunUpdateSuccessful()
    {
        Application app = createMock(Application.class);
        ChangeCurrentSongActionTaskTestImpl task =
                new ChangeCurrentSongActionTaskTestImpl(ctrl, true);
        ctrl.disablePlayerActions();
        EasyMock.expect(ctrl.getApplication()).andReturn(app);
        app.execute(task);
        replayAll();
        task.run();
        assertEquals("Wrong number of calls", 1,
                task.getUpdatePlaylistIndexCalls());
        verifyAll();
    }

    /**
     * Tests the execution of the action task if the playlist index could not be
     * updated.
     */
    @Test
    public void testRunUpdateNotSuccessful()
    {
        ChangeCurrentSongActionTaskTestImpl task =
                new ChangeCurrentSongActionTaskTestImpl(ctrl, false);
        replayAll();
        task.run();
        assertEquals("Wrong number of calls", 1,
                task.getUpdatePlaylistIndexCalls());
        verifyAll();
    }

    /**
     * Tests the execution of the command.
     */
    @Test
    public void testExecute() throws Exception
    {
        ctrl.shutdownPlayer();
        ctrl.setUpAudioPlayer(0, 0);
        replayAll();
        ChangeCurrentSongActionTaskTestImpl task =
                new ChangeCurrentSongActionTaskTestImpl(ctrl, true);
        task.execute();
        verifyAll();
    }

    /**
     * A concrete test implementation of the action task class.
     */
    private static class ChangeCurrentSongActionTaskTestImpl extends
            AbstractChangeCurrentSongActionTask
    {
        /** A flag whether updating of the playlist index was successful. */
        private final boolean updateResult;

        /** The number of invocations of the updatePlaylistIndex() method. */
        private int updateCalls;

        /**
         * Creates a new instance of {@code ChangeCurrentSongActionTaskTestImpl}
         * .
         *
         * @param ctrl the controller
         * @param updateSuccessFlag the flag to be returned by
         *        {@code updatePlaylistIndex()}
         */
        public ChangeCurrentSongActionTaskTestImpl(MainWndController ctrl,
                boolean updateSuccessFlag)
        {
            super(ctrl);
            updateResult = updateSuccessFlag;
        }

        /**
         * Returns the number of invocations of the updatePlaylistIndex()
         * method.
         *
         * @return the number of calls
         */
        public int getUpdatePlaylistIndexCalls()
        {
            return updateCalls;
        }

        /**
         * Records this invocation and returns the flag passed to the
         * constructor.
         */
        @Override
        protected boolean updatePlaylistIndex()
        {
            updateCalls++;
            return updateResult;
        }
    }
}
