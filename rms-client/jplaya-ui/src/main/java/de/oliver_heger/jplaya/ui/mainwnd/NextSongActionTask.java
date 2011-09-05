package de.oliver_heger.jplaya.ui.mainwnd;

/**
 * <p>
 * A specialized action task which skips to the next song in the playlist.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class NextSongActionTask extends AbstractControllerActionTask
{
    /**
     * Creates a new instance of {@code NextSongActionTask} and sets the
     * reference to the controller.
     *
     * @param ctrl the controller (must not be <b>null</b>)
     * @throws NullPointerException if the controller is <b>null</b>
     */
    public NextSongActionTask(MainWndController ctrl)
    {
        super(ctrl);
    }

    /**
     * Executes this task. This implementation delegates to the controller to
     * call the skip method on the audio player.
     */
    @Override
    public void run()
    {
        getController().skipToNextSong();
    }
}
