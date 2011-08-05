package de.oliver_heger.jplaya.ui.mainwnd;

import java.util.List;

import net.sf.jguiraffe.gui.builder.utils.MessageOutput;
import net.sf.jguiraffe.gui.cmd.CommandBase;

/**
 * <p>
 * A command class for initializing a new playlist.
 * </p>
 * <p>
 * This command triggers scanning of the media directory and the creation of a
 * new playlist. If this was successful, the audio player is started. In case of
 * an error, a message box is displayed.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class InitPlaylistCommand extends CommandBase
{
    /** Constant for the resource ID for the error message box title. */
    static final String RES_ERR_INIT_TITLE = "err_initplaylist_title";

    /** Constant for the resource ID for the error message box content. */
    static final String RES_ERR_INIT_MSG = "err_initplaylist_msg";

    /** Stores a reference to the main controller. */
    private final MainWndController controller;

    /** Stores the content of the playlist model. */
    private List<PlaylistItem> playlistItems;

    /**
     * Creates a new instance of {@code InitPlaylistCommand} and sets the
     * reference to the controller.
     *
     * @param ctrl the main controller (must not be <b>null</b>)
     * @throws NullPointerException if the controller is <b>null</b>
     */
    public InitPlaylistCommand(MainWndController ctrl)
    {
        if (ctrl == null)
        {
            throw new NullPointerException("Controller must not be null!");
        }
        controller = ctrl;
    }

    /**
     * Executes this command. This implementation just calls delegates to the
     * controller. The playlist is initialized in the background thread.
     *
     * @throws Exception if an error occurs
     */
    @Override
    public void execute() throws Exception
    {
        controller.resetPlaylistUI();
        controller.initAudioEngine();
        playlistItems = controller.setUpPlaylistItems();
    }

    /**
     * Updates the UI after background processing. This implementation checks
     * whether the playlist could be initialized successfully. If this is the
     * case, the player is already playing. Otherwise a message box with an
     * error message is displayed. Also, it has to ensured that actions related
     * to the player are updated. This is done automatically if the player was
     * started successfully (when the first events from the player arrive).
     * Otherwise, an update has to be performed manually.
     */
    @Override
    protected void performGUIUpdate()
    {
        if (getException() != null)
        {
            controller.updatePlayerActionStates();
            controller
                    .getApplication()
                    .getApplicationContext()
                    .messageBox(RES_ERR_INIT_MSG, RES_ERR_INIT_TITLE,
                            MessageOutput.MESSAGE_ERROR, MessageOutput.BTN_OK);
        }
        else
        {
            controller.initializePlaylistModel(getPlaylistItems());
        }
    }

    /**
     * Returns the list with items of the current playlist.
     *
     * @return the playlist items
     */
    List<PlaylistItem> getPlaylistItems()
    {
        return playlistItems;
    }
}
