package de.oliver_heger.jplaya.ui.mainwnd;

/**
 * <p>
 * A task class for initializing a new playlist.
 * </p>
 * <p>
 * This implementation just delegates to the main controller.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class InitPlaylistTask extends AbstractControllerActionTask
{
    /**
     * Creates a new instance of {@code InitPlaylistTask} and sets the reference
     * to the controller.
     *
     * @param ctrl the main controller (must not be <b>null</b>)
     * @throws NullPointerException if the controller is <b>null</b>
     */
    public InitPlaylistTask(MainWndController ctrl)
    {
        super(ctrl);
    }

    /**
     * Executes this task. This implementation just calls the main controller.
     */
    @Override
    public void run()
    {
        getController().initPlaylist();
    }
}
