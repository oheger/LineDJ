package de.oliver_heger.jplaya.ui.mainwnd;

/**
 * <p>
 * A specialized action task which starts the audio player.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class StartPlaybackActionTask extends AbstractControllerActionTask
{
    /**
     * Creates a new instance of {@code StartPlaybackActionTask} and sets the
     * reference to the controller.
     *
     * @param ctrl the controller (must not be <b>null</b>)
     * @throws NullPointerException if the controller is <b>null</b>
     */
    public StartPlaybackActionTask(MainWndController ctrl)
    {
        super(ctrl);
    }

    /**
     * Executes this task. This implementation delegates to the controller to
     * start the audio player.
     */
    @Override
    public void run()
    {
        getController().startPlayback();
    }
}
