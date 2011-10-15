package de.oliver_heger.mediastore.client.pages.overview;

import java.util.HashMap;
import java.util.Map;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.logical.shared.SelectionEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.TabLayoutPanel;
import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.client.ImageResources;
import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;

/**
 * <p>
 * A specialized component representing the main overview page.
 * </p>
 * <p>
 * This page mainly consists of a tab panel. The different tabs contain overview
 * tables for the different media types.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class OverviewPage extends Composite implements SearchListener
{
    /** Constant for the label of the remove action. */
    private static final String ACTION_REMOVE = "Remove";

    /** The binder used for building this component. */
    private static MyUiBinder binder = GWT.create(MyUiBinder.class);

    /** The tab panel. */
    @UiField
    TabLayoutPanel tabPanel;

    /** The table for the artists. */
    @UiField
    OverviewTable tabArtists;

    /** The table for the songs. */
    @UiField
    OverviewTable tabSongs;

    /** The table for the albums. */
    @UiField
    OverviewTable tabAlbums;

    /** A map with handlers for processing search queries. */
    private Map<OverviewTable, OverviewData> overviewTables;

    /** Holds a reference to the page manager. */
    private PageManager pageManager;

    /** The image resources. */
    private ImageResources imageResources;

    /**
     * Creates a new instance of {@code OverviewPage}.
     */
    public OverviewPage()
    {
        initWidget(binder.createAndBindUi(this));
    }

    /**
     * Returns a reference to the {@link RMSPageManager}. Using this component
     * it is possible to navigate to other pages.
     *
     * @return the page manager
     */
    public PageManager getPageManager()
    {
        return pageManager;
    }

    /**
     * Returns the object for accessing image resources.
     *
     * @return the object with image resources
     */
    public ImageResources getImageResources()
    {
        return imageResources;
    }

    /**
     * Handles a search request. This method is called when the user enters a
     * search text in one of the overview tables and hits the search button. It
     * delegates to the query handler associated with the overview table.
     *
     * @param source the overview table which is the source of the request
     * @param params the search parameters
     */
    @Override
    public void searchRequest(OverviewTable source, MediaSearchParameters params)
    {
        AbstractOverviewQueryHandler<?> handler = fetchQueryHandler(source);
        handler.handleQuery(params, null);
    }

    /**
     * Initializes this component.
     *
     * @param pm a reference to the page manager
     */
    public void initialize(PageManager pm)
    {
        pageManager = pm;
        imageResources = GWT.create(ImageResources.class);

        initQueryHandlers();
        initElementHandlers();
        ((AbstractOverviewTable<?>) getTableWidget(3)).initialize(pm);
        ensureOverviewTableInitialized(tabPanel.getSelectedIndex());
    }

    /**
     * Creates the handler for artist queries. This method is called when the
     * map with the query handlers is initialized.
     *
     * @return the query handler for artists
     */
    protected AbstractOverviewQueryHandler<?> createArtistQueryHandler()
    {
        return new ArtistQueryHandler(tabArtists);
    }

    /**
     * Creates the handler for song queries. This method is called when a query
     * for songs is initiated.
     *
     * @return the query handler for songs
     */
    protected AbstractOverviewQueryHandler<?> createSongQueryHandler()
    {
        return new SongQueryHandler(tabSongs);
    }

    /**
     * Creates the handler for album queries. This method is called when a query
     * for albums is initiated.
     *
     * @return the query handler for albums
     */
    protected AbstractOverviewQueryHandler<?> createAlbumQueryHandler()
    {
        return new AlbumQueryHandler(tabAlbums);
    }

    /**
     * Initializes the map with query handlers. This method also registers this
     * object as search listener at all overview tables.
     */
    void initQueryHandlers()
    {
        overviewTables = new HashMap<OverviewTable, OverviewData>();
        overviewTables.put(tabArtists, new OverviewData(tabArtists,
                createArtistQueryHandler()));
        tabArtists.setSearchListener(this);
        overviewTables.put(tabSongs, new OverviewData(tabSongs,
                createSongQueryHandler()));
        tabSongs.setSearchListener(this);
        overviewTables.put(tabAlbums, new OverviewData(tabAlbums,
                createAlbumQueryHandler()));
        tabAlbums.setSearchListener(this);
    }

    /**
     * Initializes the single and multiple element handlers for the overview
     * tables.
     */
    void initElementHandlers()
    {
        tabArtists.addSingleElementHandler(getImageResources().viewDetails(),
                new OpenPageSingleElementHandler(getPageManager(),
                        Pages.ARTISTDETAILS));
        tabSongs.addSingleElementHandler(getImageResources().viewDetails(),
                new OpenPageSingleElementHandler(getPageManager(),
                        Pages.SONGDETAILS));
        tabAlbums.addSingleElementHandler(getImageResources().viewDetails(),
                new OpenPageSingleElementHandler(getPageManager(),
                        Pages.ALBUMDETAILS));

        RemoveElementHandler removeArtistHandler =
                new RemoveElementHandler(createRemoveArtistHandler(),
                        tabArtists, null);
        RemoveElementHandler removeAlbumHandler =
                new RemoveElementHandler(createRemoveAlbumHandler(), tabAlbums, null);
        RemoveElementHandler removeSongHander =
                new RemoveElementHandler(createRemoveSongHandler(), tabSongs, null);
        tabArtists.addSingleElementHandler(getImageResources().removeItem(),
                removeArtistHandler);
        tabArtists.addMultiElementHandler(getImageResources().removeItem(),
                ACTION_REMOVE, removeArtistHandler);
        tabAlbums.addSingleElementHandler(getImageResources().removeItem(),
                removeAlbumHandler);
        tabAlbums.addMultiElementHandler(getImageResources().removeItem(),
                ACTION_REMOVE, removeAlbumHandler);
        tabSongs.addSingleElementHandler(getImageResources().removeItem(),
                removeSongHander);
        tabSongs.addMultiElementHandler(getImageResources().removeItem(),
                ACTION_REMOVE, removeSongHander);
    }

    /**
     * Returns the query handler for the specified overview table. The query
     * handlers are initialized when an instance is created. They are then used
     * to process search queries.
     *
     * @param table the overview table in question
     * @return the query handler for this overview table
     */
    AbstractOverviewQueryHandler<?> fetchQueryHandler(OverviewTable table)
    {
        OverviewData overviewData = overviewTables.get(table);
        return overviewData.getOverviewHandler();
    }

    /**
     * The selection of the tab panel has changed. We check whether the overview
     * panel for this tab has already been initialized. If not, it is
     * initialized now. This causes an initial query to be sent to the server
     * when the tab is opened for the first time.
     *
     * @param event the selection event
     */
    @UiHandler("tabPanel")
    void tabSelectionChanged(SelectionEvent<Integer> event)
    {
        ensureOverviewTableInitialized(event.getSelectedItem());
    }

    /**
     * Returns the widget of the tab pane with the specified index.
     *
     * @param index the index in the tab pane
     * @return the widget at this index
     */
    Widget getTableWidget(int index)
    {
        return tabPanel.getWidget(index);
    }

    /**
     * Ensures that the overview table with the given index is initialized. This
     * method is called when the application starts and when the selection of
     * the tab panel with the overview tables changes.
     *
     * @param index the current index of the tab panel
     */
    private void ensureOverviewTableInitialized(int index)
    {
        OverviewData overviewData = overviewTables.get(getTableWidget(index));
        if (overviewData != null)
        {
            overviewData.ensureInit();
        }
        else
        {
            Window.alert("Could not initialize overview table at " + index);
        }
    }

    /**
     * Creates a remove service handler for removing an artist.
     *
     * @return the handler
     */
    private RemoveServiceHandler createRemoveArtistHandler()
    {
        return new RemoveServiceHandler()
        {
            @Override
            public void removeElement(BasicMediaServiceAsync service,
                    Object elemID, AsyncCallback<Boolean> callback)
            {
                service.removeArtist((Long) elemID, callback);
            }
        };
    }

    /**
     * Creates a remove service handler for removing an album.
     *
     * @return the handler
     */
    private RemoveServiceHandler createRemoveAlbumHandler()
    {
        return new RemoveServiceHandler()
        {
            @Override
            public void removeElement(BasicMediaServiceAsync service,
                    Object elemID, AsyncCallback<Boolean> callback)
            {
                service.removeAlbum((Long) elemID, callback);
            }
        };
    }

    /**
     * Creates a remove service handler for removing a song.
     *
     * @return the handler
     */
    private RemoveServiceHandler createRemoveSongHandler()
    {
        return new RemoveServiceHandler()
        {
            @Override
            public void removeElement(BasicMediaServiceAsync service,
                    Object elemID, AsyncCallback<Boolean> callback)
            {
                service.removeSong(String.valueOf(elemID), callback);
            }
        };
    }

    /**
     * The specific UI binder interface for this page component.
     */
    interface MyUiBinder extends UiBinder<Widget, OverviewPage>
    {
    }

    /**
     * A simple data class for storing information about an overview table.
     * Objects of this class are created for all overview tables managed by
     * {@code RemoteMediaStore}. In addition to storing the handler object for
     * the overview table, this class keeps track if the table has already been
     * initialized.
     */
    private static class OverviewData
    {
        /** The overview table control. */
        private final OverviewTable table;

        /** Stores the handler for the overview table. */
        private final AbstractOverviewQueryHandler<?> overviewHandler;

        /** A flag whether the table has already been initialized. */
        private boolean initialized;

        /**
         * Creates a new instance of {@code OverviewData} and initializes it.
         *
         * @param tab the overview table object
         * @param handler the handler for the overview table
         */
        public OverviewData(OverviewTable tab,
                AbstractOverviewQueryHandler<?> handler)
        {
            table = tab;
            overviewHandler = handler;
        }

        /**
         * Returns the handler for the overview table managed by this data
         * object.
         *
         * @return the overview table handler
         */
        public AbstractOverviewQueryHandler<?> getOverviewHandler()
        {
            return overviewHandler;
        }

        /**
         * Ensures that the represented overview table has been initialized. If
         * this has not been the case, an initial refresh operation is performed
         * on the table.
         */
        public void ensureInit()
        {
            if (!initialized)
            {
                initialized = true;
                table.refresh();
            }
        }
    }
}
