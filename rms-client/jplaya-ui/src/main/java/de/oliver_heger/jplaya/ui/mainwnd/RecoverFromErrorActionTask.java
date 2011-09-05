package de.oliver_heger.jplaya.ui.mainwnd;

import de.oliver_heger.jplaya.playlist.PlaylistManager;

/**
 * <p>
 * A specialized action task which allows recovering from an error reported by
 * the audio player.
 * </p>
 * <p>
 * When an error event from the audio player arrives the player may be in an
 * inconsistent state. The error may be caused by an audio file containing
 * unexpected data. In this case the buffer filled with temporary audio data is
 * probably corrupt.
 * </p>
 * <p>
 * Therefore, it is not possible to simply skip to the next song in the
 * playlist. Rather, the player has to be shutdown and newly constructed. Then
 * it is safe to start playback with the next song in the playlist. This is
 * exactly what the {@code AbstractChangeCurrentSongActionTask} base class
 * already implements.
 * </p>
 * <p>
 * This concrete subclass changes the playlist index by simply moving to the
 * next song. There is a possible corner case if the song causing the error was
 * the last song in the playlist. In this case a playlist end event is
 * generated.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class RecoverFromErrorActionTask extends
        AbstractChangeCurrentSongActionTask
{
    /**
     * Creates a new instance of {@code RecoverFromErrorActionTask} and sets the
     * reference to the main controller.
     *
     * @param ctrl the {@code MainWndController} (must not be <b>null</b>)
     * @throws NullPointerException if the controller is <b>null</b>
     */
    public RecoverFromErrorActionTask(MainWndController ctrl)
    {
        super(ctrl);
    }

    /**
     * Updates the playlist index. Moves to the next song. If this is not
     * possible because the end of the playlist is reached, the controller is
     * notified about this fact.
     *
     * @return a flag whether the index could be updated
     */
    @Override
    protected boolean updatePlaylistIndex()
    {
        PlaylistManager pm =
                getController().getPlaylistController().getPlaylistManager();
        if (pm.nextSong())
        {
            return true;
        }
        else
        {
            getController().playListEnds(null);
            return false;
        }
    }
}
