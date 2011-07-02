package de.oliver_heger.jplaya.ui.mainwnd;

/**
 * <p>
 * A base class for action tasks that manipulate the {@link MainWndController}.
 * </p>
 * <p>
 * There are a couple of actions which simply have to delegate to methods of the
 * main controller class. The tasks for these actions can extend this base
 * class. The base class already provides functionality for managing the
 * reference to the controller.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public abstract class AbstractControllerActionTask implements Runnable
{
    /** Stores the reference to the controller. */
    private final MainWndController controller;

    /**
     * Creates a new instance of {@code AbstractControllerActionTask} and sets
     * the reference to the controller.
     *
     * @param ctrl the controller (must not be <b>null</b>)
     * @throws NullPointerException if the controller is <b>null</b>
     */
    protected AbstractControllerActionTask(MainWndController ctrl)
    {
        if (ctrl == null)
        {
            throw new NullPointerException("Controller must not be null!");
        }
        controller = ctrl;
    }

    /**
     * Returns the reference to the controller.
     *
     * @return the controller
     */
    public MainWndController getController()
    {
        return controller;
    }
}
