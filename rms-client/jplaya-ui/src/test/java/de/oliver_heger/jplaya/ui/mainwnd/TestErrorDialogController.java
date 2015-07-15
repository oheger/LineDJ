package de.oliver_heger.jplaya.ui.mainwnd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.util.Timer;
import java.util.TimerTask;

import net.sf.jguiraffe.gui.builder.action.FormAction;
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler;
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer;

import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.mutable.MutableObject;
import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.easymock.IAnswer;
import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@code ErrorDialogController}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestErrorDialogController extends EasyMockSupport
{
    /** The mock for the synchronizer. */
    private GUISynchronizer sync;

    /** The mock for the action. */
    private FormAction action;

    /** The mock for the text handler. */
    private StaticTextHandler handler;

    @Before
    public void setUp() throws Exception
    {
        sync = createMock(GUISynchronizer.class);
        action = createMock(FormAction.class);
        handler = createMock(StaticTextHandler.class);
    }

    /**
     * Prepares the synchronizer mock to be invoked. Installs an answer which
     * invokes the runnable.
     */
    private void setUpSynchronizer()
    {
        sync.asyncInvoke(EasyMock.anyObject(Runnable.class));
        EasyMock.expectLastCall().andAnswer(new SyncAnswer());
    }

    /**
     * Tries to create an instance without a synchronizer.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoSync()
    {
        new ErrorDialogController(null, action, handler);
    }

    /**
     * Tries to create an instance without an action.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoAction()
    {
        new ErrorDialogController(sync, null, handler);
    }

    /**
     * Tries to create an instance without a handler.
     */
    @Test(expected = NullPointerException.class)
    public void testInitNoHandler()
    {
        new ErrorDialogController(sync, action, null);
    }

    /**
     * Tests whether a timer can be created.
     */
    @Test
    public void testCreateTimer()
    {
        replayAll();
        ErrorDialogController ctrl =
                new ErrorDialogController(sync, action, handler);
        Timer timer = ctrl.createTimer();
        assertNotNull("No timer", timer);
    }

    /**
     * Tests the task for the count-down.
     */
    @Test
    public void testCreateTimerTaskRun()
    {
        replayAll();
        final MutableInt counter = new MutableInt();
        ErrorDialogController ctrl =
                new ErrorDialogController(sync, action, handler)
                {
                    @Override
                    void countDown()
                    {
                        counter.increment();
                    }
                };
        TimerTask timerTask = ctrl.createTimerTask();
        timerTask.run();
        assertEquals("Wrong invocations of countDown()", 1, counter.getValue()
                .intValue());
    }

    /**
     * Tests the initialization of the controller.
     */
    @Test
    public void testInitializeController()
    {
        final Timer timer = createMock(Timer.class);
        final TimerTask task = createMock(TimerTask.class);
        handler.setText(String.valueOf(ErrorDialogController.COUNT_DOWN_TIME));
        timer.scheduleAtFixedRate(task, 1000, 1000);
        replayAll();
        ErrorDialogController ctrl =
                new ErrorDialogController(sync, action, handler)
                {
                    @Override
                    Timer createTimer()
                    {
                        return timer;
                    }

                    @Override
                    TimerTask createTimerTask()
                    {
                        return task;
                    }
                };
        ctrl.initializeController();
        assertSame("Wrong timer", timer, ctrl.getTimer());
        assertEquals("Wrong count-down value",
                ErrorDialogController.COUNT_DOWN_TIME, ctrl.getCountDownValue());
        verifyAll();
    }

    /**
     * Tests an invocation of the countDown() method.
     */
    @Test
    public void testCountDown()
    {
        final int counter = 5;
        handler.setText(String.valueOf(counter));
        setUpSynchronizer();
        replayAll();
        final MutableObject<Integer> countValue =
                new MutableObject<Integer>(Integer.valueOf(counter + 1));
        ErrorDialogController ctrl =
                new ErrorDialogController(sync, action, handler)
                {
                    @Override
                    int getCountDownValue()
                    {
                        if (countValue.getValue() != null)
                        {
                            return countValue.getValue();
                        }
                        return super.getCountDownValue();
                    }
                };
        ctrl.countDown();
        countValue.setValue(null);
        assertEquals("Wrong count down value", counter,
                ctrl.getCountDownValue());
        verifyAll();
    }

    /**
     * Tests countDown() if the end of the count-down is reached.
     */
    @Test
    public void testCountDownZeroReached()
    {
        setUpSynchronizer();
        replayAll();
        final MutableInt closeCount = new MutableInt();
        ErrorDialogController ctrl =
                new ErrorDialogController(sync, action, handler)
                {
                    @Override
                    int getCountDownValue()
                    {
                        return 1;
                    }

                    @Override
                    protected void closeForm()
                    {
                        closeCount.increment();
                    }
                };
        ctrl.countDown();
        assertEquals("Wrong number of closeForm() calls", 1, closeCount
                .getValue().intValue());
        verifyAll();
    }

    /**
     * Tests whether the recover action is triggered if the dialog has been
     * committed. (This means that the user clicked the OK button.)
     */
    @Test
    public void testTriggerRecoverActionCommitted()
    {
        action.execute(null);
        ErrorDialogController ctrl =
                new ErrorDialogControllerTriggerActionTestImpl(10, true);
        replayAll();
        ctrl.handleClose();
        verifyAll();
    }

    /**
     * Tests whether the recover action is triggered if the count-down has
     * reached 0.
     */
    @Test
    public void testTriggerRecoverActionTimeout()
    {
        action.execute(null);
        ErrorDialogController ctrl =
                new ErrorDialogControllerTriggerActionTestImpl(0, false);
        replayAll();
        ctrl.handleClose();
        verifyAll();
    }

    /**
     * Tests a constellation in which the recover action should not be
     * triggered.
     */
    @Test
    public void testTriggerRecoverActionNotNecessary()
    {
        ErrorDialogController ctrl =
                new ErrorDialogControllerTriggerActionTestImpl(5, false);
        replayAll();
        ctrl.handleClose();
        verifyAll();
    }

    /**
     * An answer implementation for testing whether the synchronizer is used
     * correctly.
     */
    private static class SyncAnswer implements IAnswer<Object>
    {
        /**
         * Just invokes the Runnable passed to the synchronizer.
         */
        @Override
        public Object answer() throws Throwable
        {
            Runnable r = (Runnable) EasyMock.getCurrentArguments()[0];
            r.run();
            return null;
        }
    }

    /**
     * A test implementation of the controller for checking whether action is
     * correctly invoked.
     */
    private class ErrorDialogControllerTriggerActionTestImpl extends
            ErrorDialogController
    {
        /** A mock for the timer. */
        private final Timer mockTimer;

        /** The value of the count-down counter. */
        private final int counter;

        /** The committed flag. */
        private final boolean committed;

        /**
         * Creates a new instance of
         * {@code ErrorDialogControllerTriggerActionTestImpl} and sets the
         * values to be returned by overloaded methods.
         *
         * @param countDown the count-down value
         * @param commitFlag the committed flag
         */
        public ErrorDialogControllerTriggerActionTestImpl(int countDown,
                boolean commitFlag)
        {
            super(sync, action, handler);
            mockTimer = createMock(Timer.class);
            mockTimer.cancel();
            counter = countDown;
            committed = commitFlag;
        }

        /**
         * Returns the value passed to the constructor.
         */
        @Override
        int getCountDownValue()
        {
            return counter;
        }

        /**
         * Returns the mock timer.
         */
        @Override
        Timer getTimer()
        {
            return mockTimer;
        }

        /**
         * Returns the value passed to the constructor.
         */
        @Override
        protected boolean isCommitted()
        {
            return committed;
        }
    }
}
