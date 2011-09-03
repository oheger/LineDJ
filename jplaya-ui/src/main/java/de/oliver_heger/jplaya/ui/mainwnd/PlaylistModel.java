package de.oliver_heger.jplaya.ui.mainwnd;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import net.sf.jguiraffe.gui.builder.components.model.TableHandler;
import net.sf.jguiraffe.gui.forms.Form;
import de.oliver_heger.jplaya.engine.mediainfo.SongDataEvent;
import de.oliver_heger.jplaya.engine.mediainfo.SongDataManager;
import de.oliver_heger.mediastore.service.ObjectFactory;
import de.oliver_heger.mediastore.service.SongData;

/**
 * <p>
 * A class representing the model of the audio player application.
 * </p>
 * <p>
 * This class stores information about the currently played song and all songs
 * in the playlist. It is used as table model for the table with the playlist
 * and to populate the detail view for the current song.
 * </p>
 * <p>
 * When a new playlist is constructed the class is initialized with empty
 * objects of type {@code PlaylistItem}. Media information is fetched in
 * background. If new data becomes available, the model is updated.
 * </p>
 * <p>
 * Although the model must be accessed in the EDT exclusively, there are some
 * race conditions that can occur. It is possible that newly fetched media
 * information or events from the audio player arrive before the model is fully
 * initialized and ready to serve. In this case, requests cannot be processed
 * immediately, but have to be buffered. When initialization is complete they
 * are processed.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class PlaylistModel
{
    /** A factory for creating song data objects. */
    private static final ObjectFactory DATA_FACTORY = new ObjectFactory();

    /** A special item object that is used for clearing the form. */
    private final PlaylistItem clearItem;

    /** The current playlist context. */
    private final PlaylistContext playlistContext;

    /** The form to be populated. */
    private final Form form;

    /** The table handler. */
    private final TableHandler tableHandler;

    /** The currently used song data manager. */
    private SongDataManager songDataManager;

    /** A list with events that have to be processed. */
    private List<SongDataEvent> pendingEvents;

    /** The list with the data of the model. */
    private List<PlaylistItem> modelData;

    /** The last current song index. */
    private int lastCurrentSongIndex;

    /** A flag whether an update operation is pending. */
    private boolean updatePending;

    /**
     * Creates a new instance of {@code PlaylistModel} and initializes it.
     *
     * @param frm the form object representing the main UI (must not be
     *        <b>null</b>)
     * @param tab the table handler for the playlist table (must not be
     *        <b>null</b>)
     * @throws NullPointerException if a required parameter is missing
     */
    public PlaylistModel(Form frm, TableHandler tab)
    {
        if (frm == null)
        {
            throw new NullPointerException("Form must not be null!");
        }
        if (tab == null)
        {
            throw new NullPointerException("Table handler must not be null!");
        }

        form = frm;
        tableHandler = tab;
        playlistContext = initPlaylistContext();
        clearItem = initClearItem();
        lastCurrentSongIndex = -1;
    }

    /**
     * Returns an object with context information about the current playlist.
     *
     * @return the current playlist context
     */
    public PlaylistContext getPlaylistContext()
    {
        return playlistContext;
    }

    /**
     * New media information has been retrieved. This method updates the model
     * correspondingly. If necessary, the UI is updated. First it has to be
     * checked whether the playlist is already fully constructed.
     *
     * @param event the event
     */
    public void processSongDataEvent(SongDataEvent event)
    {
        if (isCurrentSongDataManager(event))
        {
            handleEvent(event);
        }
        else
        {
            initializePending();
            pendingEvents.add(event);
        }
    }

    /**
     * Performs an update of the UI. First it has to be checked whether the
     * playlist is already fully constructed.
     *
     * @param sdm the current {@code SongDataManager}
     */
    public void updateUI(SongDataManager sdm)
    {
        if (isCurrentSongDataManager(sdm))
        {
            updateForm();
        }
        else
        {
            updatePending = true;
        }
    }

    /**
     * Initializes the playlist. This method is called when the playlist model
     * has been fully constructed. The items are passed in. Also a reference to
     * the current {@code SongDataManager} is provided. It may be the case that
     * operations have been requested before the initialization. In this case
     * they have to be processed now.
     *
     * @param sdm the {@code SongDataManager}
     * @param itemList the list with the content of this model
     */
    public void initialize(SongDataManager sdm, List<PlaylistItem> itemList)
    {
        songDataManager = sdm;
        modelData = itemList;
        tableHandler.getModel().clear();
        tableHandler.getModel().addAll(itemList);
        tableHandler.tableDataChanged();

        processPendingOperations();
    }

    /**
     * Creates a list with uninitialized item objects for the content of this
     * model.
     *
     * @param songURIs the collection with the URIs of the current playlist
     * @return the list with corresponding playlist item objects
     */
    public List<PlaylistItem> createModelItems(Collection<String> songURIs)
    {
        List<PlaylistItem> items = new ArrayList<PlaylistItem>(songURIs.size());
        SongData data = DATA_FACTORY.createSongData();
        int idx = 0;

        for (String uri : songURIs)
        {
            PlaylistItem item = new PlaylistItem();
            item.setPlaylistContext(getPlaylistContext());
            item.setSongData(data);
            item.setIndex(idx);
            item.setUri(uri);
            items.add(item);
            idx++;
        }

        return items;
    }

    /**
     * Handles the specified event. Updates the table model. If the event
     * affects the current song, it is updated, too.
     *
     * @param event the event
     */
    void handleEvent(SongDataEvent event)
    {
        int index = ((Number) event.getMediaFileID()).intValue();
        PlaylistItem item = modelData.get(index);
        item.setSongData(songDataManager.getDataForID(event.getMediaFileID()));
        tableHandler.rowsUpdated(index, index);

        if (index == getPlaylistContext().getCurrentSongIndex())
        {
            updateForm();
        }
    }

    /**
     * Updates the form with the data of the current song. The selected index of
     * the table is also updated if the index of the current song has changed.
     */
    void updateForm()
    {
        int index = getPlaylistContext().getCurrentSongIndex();
        PlaylistItem item = (index >= 0) ? modelData.get(index) : clearItem;
        form.initFields(item);

        if (index != lastCurrentSongIndex)
        {
            lastCurrentSongIndex = index;
            if (index >= 0)
            {
                tableHandler.setSelectedIndex(index);
            }
        }
    }

    /**
     * Returns the current {@code SongDataManager}.
     *
     * @return the current {@code SongDataManager}
     */
    SongDataManager getCurrentSongDataManager()
    {
        return songDataManager;
    }

    /**
     * Initializes the playlist context.
     *
     * @return the fully initialized context
     */
    private PlaylistContext initPlaylistContext()
    {
        PlaylistContext ctx = new PlaylistContext();
        ctx.setCurrentSongIndex(-1);
        ctx.setPlaylistInfo(NullPlaylistInfo.INSTANCE);
        return ctx;
    }

    /**
     * Initializes the special {@code PlaylistItem} for clearing the UI.
     *
     * @return the clear playlist item
     */
    private PlaylistItem initClearItem()
    {
        PlaylistItem cli = new PlaylistItem();
        cli.setSongData(DATA_FACTORY.createSongData());
        cli.setPlaylistContext(playlistContext);
        return cli;
    }

    /**
     * Tests whether the specified {@code SongDataManager} is the current
     * manager. If this is not the case, the playlist has changed in the
     * meantime.
     *
     * @param sdm the {@code SongDataManager} to check
     * @return a flag whether this is the current song data manager
     */
    private boolean isCurrentSongDataManager(SongDataManager sdm)
    {
        return getCurrentSongDataManager() == sdm;
    }

    /**
     * Tests whether the specified event is related to the current
     * {@code SongDataManager}. If this is not the case, the playlist has
     * changed in the meantime.
     *
     * @param event the {@code SongDataEvent} to be checked
     * @return a flag whether the event refers to the current
     *         {@code SongDataManager}
     */
    private boolean isCurrentSongDataManager(SongDataEvent event)
    {
        return isCurrentSongDataManager(event.getManager());
    }

    /**
     * Returns a flag whether events are pending. When {@code initialize()} is
     * called it has to be checked whether song data events were received before
     * the playlist was fully initialized. This is done through this method.
     *
     * @return a flag whether there are pending operations
     */
    private boolean isEventsPending()
    {
        return pendingEvents != null;
    }

    /**
     * Initializes data structures that indicate that operations are pending.
     * This method is called if operations are to be executed before the model
     * has been fully initialized.
     */
    private void initializePending()
    {
        if (pendingEvents == null)
        {
            pendingEvents = new LinkedList<SongDataEvent>();
        }
    }

    /**
     * Processes pending operations.
     */
    private void processPendingOperations()
    {
        if (isEventsPending())
        {
            for (SongDataEvent event : pendingEvents)
            {
                handleEvent(event);
            }
            pendingEvents = null;
        }

        if (updatePending)
        {
            updateForm();
            updatePending = false;
        }
    }
}
