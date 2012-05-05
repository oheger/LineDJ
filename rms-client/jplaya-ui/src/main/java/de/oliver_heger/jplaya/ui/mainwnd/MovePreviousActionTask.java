package de.oliver_heger.jplaya.ui.mainwnd;

/**
 * <p>
 * A specialized action task class for the action which moves to the previous
 * song.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class MovePreviousActionTask extends AbstractControllerActionTask
{
    /**
     * Creates a new instance of {@code MovePreviousActionTask} and sets the
     * controller.
     *
     * @param ctrl the {@code MainWndController} (must not be <b>null</b>)
     * @throws NullPointerException if the controller is <b>null</b>
     */
    public MovePreviousActionTask(MainWndController ctrl)
    {
        super(ctrl);
    }

    /**
     * Executes this task. This implementation just delegates to the controller.
     */
    @Override
    public void run()
    {
        getController().moveBackward();
    }
}
