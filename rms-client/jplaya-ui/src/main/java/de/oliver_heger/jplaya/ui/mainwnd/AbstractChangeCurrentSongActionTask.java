package de.oliver_heger.jplaya.ui.mainwnd;

import net.sf.jguiraffe.gui.cmd.CommandBase;

/**
 * <p>
 * An abstract base class for action tasks which manipulate the index of the
 * currently played song in the playlist.
 * </p>
 * <p>
 * Changing the index of the song to be played is trivial only if the index
 * moves on to the next song in the playlist. In this case just some data in the
 * read buffer needs to be skipped. If the index is manipulated in an arbitrary
 * way, the buffer has to be cleared and filled again with the content of the
 * new song. The easiest way to achieve this is to shutdown the audio player
 * (which also causes the buffer to be closed) and to setup a new one with a
 * {@code PlaylistManager} updated correspondingly. This is done by this base
 * class.
 * </p>
 * <p>
 * When this task is executed (in the EDT), first all actions related to the
 * player are disabled, and the abstract {@code updatePlaylistIndex()} method is
 * called. Here concrete subclasses have the opportunity to manipulate the index
 * of the current song as desired. Then, in a background thread, the current
 * audio player is shut down, and a new one is constructed. The functionality is
 * already provided by the {@link MainWndController}; the corresponding methods
 * just have to be called.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public abstract class AbstractChangeCurrentSongActionTask extends CommandBase
        implements Runnable
{
    /** Stores a reference to the main controller. */
    private final MainWndController controller;

    /**
     * Creates a new instance of {@code AbstractChangeCurrentSongActionTask} and
     * initializes it with the reference to the main controller.
     *
     * @param ctrl the controller (must not be <b>null</b>)
     * @throws NullPointerException if the controller is <b>null</b>
     */
    protected AbstractChangeCurrentSongActionTask(MainWndController ctrl)
    {
        super(false);
        if (ctrl == null)
        {
            throw new NullPointerException("Controller must not be null!");
        }
        controller = ctrl;
    }

    /**
     * Returns the {@code MainWndController}.
     *
     * @return the {@code MainWndController}
     */
    public MainWndController getController()
    {
        return controller;
    }

    /**
     * Executes this command. This implementation performs the necessary steps
     * to construct a new audio player which starts playback at the desired
     * index in the playlist.
     *
     * @throws Exception if an error occurs
     */
    @Override
    public void execute() throws Exception
    {
        getController().shutdownPlayer();
        getController().setUpAudioPlayer(0, 0);
    }

    /**
     * Executes this task. Delegates to {@link #updatePlaylistIndex()}. Then a
     * command is executed to manipulate the audio player in a background
     * thread.
     */
    @Override
    public void run()
    {
        if (updatePlaylistIndex())
        {
            getController().disablePlayerActions();
            getController().getApplication().execute(this);
        }
    }

    /**
     * Sets the new current song index in the playlist. This method is called
     * (in the EDT) at the beginning of the execution of the action task. The
     * return value indicates whether the index was changed successfully. A
     * value of <b>true</b> means that background processing is started to
     * update the audio player. If the method returns <b>false</b>, this step is
     * skipped.
     *
     * @return a flag whether the playlist index was changed
     */
    protected abstract boolean updatePlaylistIndex();
}
