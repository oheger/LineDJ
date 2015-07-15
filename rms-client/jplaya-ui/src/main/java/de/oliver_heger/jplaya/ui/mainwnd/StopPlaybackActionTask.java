package de.oliver_heger.jplaya.ui.mainwnd;

/**
 * <p>
 * A specialized action task which stops the audio player.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class StopPlaybackActionTask extends AbstractControllerActionTask
{
    /**
     * Creates a new instance of {@code StopPlaybackActionTask} and sets the
     * reference to the controller.
     *
     * @param ctrl the controller (must not be <b>null</b>)
     * @throws NullPointerException if the controller is <b>null</b>
     */
    public StopPlaybackActionTask(MainWndController ctrl)
    {
        super(ctrl);
    }

    /**
     * Executes this task. This implementation delegates to the controller to
     * stop the audio player.
     */
    @Override
    public void run()
    {
        getController().stopPlayback();
    }

}
