package de.oliver_heger.jplaya.ui.mainwnd;

import net.sf.jguiraffe.gui.builder.components.model.TableHandler;

/**
 * <p>
 * A specialized action task for the action that moves to an arbitrary index of
 * the current playlist.
 * </p>
 * <p>
 * This task class is linked with the table component displaying the playlist.
 * It is invoked when the user makes a double click. It then determines the
 * selected index of the table and makes this song the current one to be played.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class MoveToIndexActionTask extends AbstractChangeCurrentSongActionTask
{
    /** The handler for the playlist table. */
    private final TableHandler tabHandler;

    /**
     * Creates a new instance of {@code MoveToIndexActionTask} and initializes
     * it with the {@code MainWndController} and the component handler for the
     * playlist table.
     *
     * @param ctrl the {@code MainWndController} (must not be <b>null</b>)
     * @param tab the handler for the table (must not be <b>null</b>)
     * @throws NullPointerException if a required parameter is missing
     */
    public MoveToIndexActionTask(MainWndController ctrl, TableHandler tab)
    {
        super(ctrl);
        if (tab == null)
        {
            throw new NullPointerException("Table handler must not be null!");
        }
        tabHandler = tab;
    }

    /**
     * Updates the current index of the playlist. This implementation obtains
     * the new current index from the playlist table.
     */
    @Override
    protected void updatePlaylistIndex()
    {
        int selIdx = tabHandler.getSelectedIndex();
        if (selIdx >= 0)
        {
            getController().getPlaylistController().getPlaylistManager()
                    .setCurrentSongIndex(selIdx);
        }
    }
}
