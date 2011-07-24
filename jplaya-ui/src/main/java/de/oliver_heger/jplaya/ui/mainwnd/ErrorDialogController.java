package de.oliver_heger.jplaya.ui.mainwnd;

import java.util.Timer;
import java.util.TimerTask;

import net.sf.jguiraffe.gui.builder.action.FormAction;
import net.sf.jguiraffe.gui.builder.components.model.StaticTextHandler;
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer;
import net.sf.jguiraffe.gui.builder.window.WindowEvent;
import net.sf.jguiraffe.gui.builder.window.ctrl.FormController;

/**
 * <p>
 * The controller class for the dialog window displaying the error message when
 * a playback error occurs.
 * </p>
 * <p>
 * This controller class mainly implements a count-down. If 0 is reached, the
 * dialog window is closed, and a command is executed to setup a new audio
 * player. The user can initiate the operation immediately by clicking the
 * <em>Recover</em> button. Alternatively, <em>Cancel</em> can be clicked which
 * closes the dialog without performing any action.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class ErrorDialogController extends FormController
{
    /** Constant for the count down time. */
    static final int COUNT_DOWN_TIME = 10;

    /** Constant for a second. */
    private static final long SECOND = 1000;

    /** The GUI synchronizer. */
    private final GUISynchronizer synchronizer;

    /** The recover action. */
    private final FormAction actionRecover;

    /** The handler for the count-down text field. */
    private final StaticTextHandler countDownHandler;

    /** The timer. */
    private Timer timer;

    /** The current value of the count-down. */
    private int countDownValue;

    /**
     * Creates a new instance of {@code ErrorDialogController} and initializes
     * it.
     *
     * @param sync the object for synchronizing with the EDT (must not be
     *        <b>null</b>)
     * @param actRecover the recovery action (must not be <b>null</b>)
     * @param handler the handler for the static text field used to display the
     *        count-down value (must not be <b>null</b>)
     * @throws NullPointerException if a required parameter is missing
     */
    public ErrorDialogController(GUISynchronizer sync, FormAction actRecover,
            StaticTextHandler handler)
    {
        if (sync == null)
        {
            throw new NullPointerException("GUISynchronizer must not be null!");
        }
        if (actRecover == null)
        {
            throw new NullPointerException("Recovery action must not be null!");
        }
        if (handler == null)
        {
            throw new NullPointerException("Text handler must not be null!");
        }

        synchronizer = sync;
        actionRecover = actRecover;
        countDownHandler = handler;
    }

    /**
     * The dialog window is closing. This implementation calls
     * {@code handleClose()} to ensure that the recover action is executed if
     * necessary and that necessary cleanup is done.
     *
     * @param event the window event
     */
    @Override
    public void windowClosing(WindowEvent event)
    {
        super.windowClosing(event);
        handleClose();
    }

    /**
     * The dialog window is opened. This implementation performs initialization
     * of this controller.
     *
     * @param event the window event
     */
    @Override
    public void windowOpened(WindowEvent event)
    {
        super.windowOpened(event);
        initializeController();
    }

    /**
     * Initializes this controller. This method is called when the window is
     * opened.
     */
    void initializeController()
    {
        countDownValue = COUNT_DOWN_TIME;
        updateCountDownText(COUNT_DOWN_TIME);
        scheduleCountdownTask();
    }

    /**
     * Creates the timer task. This task handles the count-down.
     *
     * @return the timer task
     */
    TimerTask createTimerTask()
    {
        return new ErrorDialogTimerTask();
    }

    /**
     * Creates the timer.
     *
     * @return the timer
     */
    Timer createTimer()
    {
        return new Timer();
    }

    /**
     * Schedules the task for the count-down functionality. This method is
     * called when the controller's window is opened.
     */
    void scheduleCountdownTask()
    {
        timer = createTimer();
        timer.scheduleAtFixedRate(createTimerTask(), SECOND, SECOND);
    }

    /**
     * Returns the timer used by this controller.
     *
     * @return the timer
     */
    Timer getTimer()
    {
        return timer;
    }

    /**
     * Returns the current count-down value.
     *
     * @return the count-down value
     */
    int getCountDownValue()
    {
        return countDownValue;
    }

    /**
     * This method is called by the timer every second. It updates the
     * count-down text field and also checks whether the end of the count-down
     * is reached.
     */
    void countDown()
    {
        synchronizer.asyncInvoke(new Runnable()
        {
            @Override
            public void run()
            {
                int count = getCountDownValue() - 1;
                countDownValue = count;
                if (count > 0)
                {
                    updateCountDownText(count);
                }
                else
                {
                    closeForm();
                }
            }
        });
    }

    /**
     * Reacts on closing of the dialog window. Executes the recover action if
     * desired by the user. This method is called when the dialog window is
     * closing. It checks whether the timeout was reached or whether the user
     * has clicked the recover button. In both cases the action is executed.
     * Otherwise, the dialog window is just closed. In any case the timer has to
     * be canceled.
     */
    void handleClose()
    {
        if (getCountDownValue() <= 0 || isCommitted())
        {
            actionRecover.execute(null);
        }
        getTimer().cancel();
    }

    /**
     * Updates the label with the count down text.
     *
     * @param value the value to be displayed
     */
    private void updateCountDownText(int value)
    {
        countDownHandler.setText(String.valueOf(value));
    }

    /**
     * A special timer task class. This class handles the count-down. It calls
     * the controller's countDown() method in regular intervals.
     *
     * @author Oliver Heger
     * @version $Id: $
     */
    private class ErrorDialogTimerTask extends TimerTask
    {
        /**
         * Executes this task. Just delegates to the controller's
         * {@code countDown()} method.
         */
        @Override
        public void run()
        {
            countDown();
        }
    }
}
