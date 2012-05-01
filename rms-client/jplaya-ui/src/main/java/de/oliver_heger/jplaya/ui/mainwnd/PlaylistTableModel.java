package de.oliver_heger.jplaya.ui.mainwnd;

import java.util.List;

import net.sf.jguiraffe.gui.builder.components.model.TableHandler;
import de.oliver_heger.splaya.PlaylistData;
import de.oliver_heger.splaya.PlaylistEvent;

/**
 * <p>
 * A class for maintaining the model for the table with the playlist.
 * </p>
 * <p>
 * An instance of this class directly accesses the collection used as model by
 * the playlist table. It gets notifications about updates of the playlist (e.g.
 * when new meta data about a song becomes available). Its task is to keep the
 * model collection up-to-date.
 * </p>
 * <p>
 * Implementation note: This class can only be accessed in the event dispatch
 * thread.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class PlaylistTableModel
{
    /** Stores the table handler. */
    private final TableHandler tableHandler;

    /**
     * Creates a new instance of {@code PlaylistTableModel} and initializes it
     * with the handler for the playlist table component.
     *
     * @param tab the handler for the playlist table
     */
    public PlaylistTableModel(TableHandler tab)
    {
        tableHandler = tab;
    }

    /**
     * Handles the specified playlist event. The collection with playlist data
     * is updated correspondingly.
     *
     * @param event the event
     */
    public void handlePlaylistEvent(PlaylistEvent event)
    {
        switch (event.getType())
        {
        case PLAYLIST_CREATED:
            setUpPlaylistDataModel(event.getPlaylistData());
            tableHandler.tableDataChanged();
            break;

        case PLAYLIST_UPDATED:
            tableHandler.rowsUpdated(event.getUpdateIndex(),
                    event.getUpdateIndex());
            break;
        }
    }

    /**
     * Fills the list with the actual table data model with item objects
     * representing the single items of a new playlist. This method is called
     * when a new playlist was created.
     *
     * @param pldata the playlist data object
     */
    private void setUpPlaylistDataModel(PlaylistData pldata)
    {
        List<Object> dataList = tableHandler.getModel();
        dataList.clear();

        for (int i = 0; i < pldata.size(); i++)
        {
            dataList.add(new PlaylistTableItem(pldata, i));
        }
    }
}
