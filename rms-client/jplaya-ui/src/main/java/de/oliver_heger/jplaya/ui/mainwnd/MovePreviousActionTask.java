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
public class MovePreviousActionTask extends AbstractChangeCurrentSongActionTask
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
     * Updates the index in the playlist. This implementation obtains the
     * {@code PlaylistController} and the current {@code PlaylistManager}. This
     * object is asked to move to the previous song.
     *
     * @return a flag whether the index could be updated successfully
     */
    @Override
    protected boolean updatePlaylistIndex()
    {
        getController().getPlaylistController().getPlaylistManager()
                .previousSong();
        return true;
    }
}
