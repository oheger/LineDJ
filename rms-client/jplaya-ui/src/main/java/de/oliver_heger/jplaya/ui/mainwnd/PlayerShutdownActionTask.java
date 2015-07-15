package de.oliver_heger.jplaya.ui.mainwnd;

/**
 * <p>
 * A specialized action task implementation which causes the application to shut
 * down.
 * </p>
 * <p>
 * This task is associated with the <em>Exit</em> menu item. It delegates to the
 * controller to initiate a shutdown of the audio player engine.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class PlayerShutdownActionTask extends AbstractControllerActionTask
{
    /**
     * Creates a new instance of {@code PlayerShutdownActionTask}.
     *
     * @param ctrl the controller (must not be <b>null</b>)
     * @throws NullPointerException if the controller is <b>null</b>
     */
    public PlayerShutdownActionTask(MainWndController ctrl)
    {
        super(ctrl);
        // TODO Auto-generated constructor stub
    }

    /**
     * Executes this task. This implementation notifies the controller that it
     * should trigger a shutdown.
     */
    @Override
    public void run()
    {
        getController().shutdown();
    }
}
