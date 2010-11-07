package de.oliver_heger.mediastore.client;

import java.util.HashMap;
import java.util.Map;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.SpanElement;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.logical.shared.SelectionEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.Anchor;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.RootLayoutPanel;
import com.google.gwt.user.client.ui.TabLayoutPanel;
import com.google.gwt.user.client.ui.Widget;

import de.oliver_heger.mediastore.shared.LoginInfo;
import de.oliver_heger.mediastore.shared.LoginService;
import de.oliver_heger.mediastore.shared.LoginServiceAsync;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchService;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;

/**
 * Entry point classes define <code>onModuleLoad()</code>.
 */
public class RemoteMediaStore implements EntryPoint,
        OverviewTable.SearchListener
{
    /** The specialized binder interface. */
    interface MyUiBinder extends UiBinder<Widget, RemoteMediaStore>
    {
    }

    /** The static instance of the binder. */
    private static MyUiBinder binder = GWT.create(MyUiBinder.class);

    /** The panel for the login hint. */
    @UiField
    Widget pnlLogin;

    /** The link for the login page. */
    @UiField
    Anchor linkLogin;

    /** The link for signing out. */
    @UiField
    Anchor linkLogout;

    /** The panel for the main application part. */
    @UiField
    Widget pnlApp;

    /** The element displaying the user name. */
    @UiField
    SpanElement spanUserName;

    /** The tab panel. */
    @UiField
    TabLayoutPanel tabPanel;

    /** The table for the artists. */
    @UiField
    OverviewTable tabArtists;

    /** The table for the songs. */
    @UiField
    OverviewTable tabSongs;

    /** The button for creating test data. */
    @UiField
    Button btnTestData;

    /** A map with handlers for processing search queries. */
    private Map<OverviewTable, OverviewData> overviewTables;

    /**
     * This is the entry point method.
     */
    public void onModuleLoad()
    {
        LoginServiceAsync loginService = GWT.create(LoginService.class);
        loginService.getLoginInfo(GWT.getHostPageBaseURL(),
                new AsyncCallback<LoginInfo>()
                {
                    /**
                     * Tests whether a user is logged in. If this is the case,
                     * the application is displayed. Otherwise, a login hint is
                     * shown.
                     *
                     * @param result the login information object retrieved from
                     *        the server
                     */
                    @Override
                    public void onSuccess(LoginInfo result)
                    {
                        Widget ui;
                        binder.createAndBindUi(RemoteMediaStore.this);
                        RootLayoutPanel root = RootLayoutPanel.get();

                        if (result.isLoggedIn())
                        {
                            spanUserName.setInnerText(result.getUserName());
                            linkLogout.setHref(result.getLogoutUrl());
                            initQueryHandlers();
                            ensureOverviewTableInitialized(tabPanel
                                    .getSelectedIndex());
                            ui = pnlApp;
                        }
                        else
                        {
                            linkLogin.setHref(result.getLoginUrl());
                            ui = pnlLogin;
                        }

                        root.add(ui);
                    }

                    /**
                     * There was an error when calling the server. Just display
                     * a message.
                     */
                    @Override
                    public void onFailure(Throwable caught)
                    {
                        Window.alert("Error when calling server!\n" + caught);
                    }
                });
    }

    /**
     * Processes a search request from an {@link OverviewTable}. This
     * implementation determines the corresponding search handler and invokes
     * it.
     *
     * @param source the source table of the search request
     * @param params the parameters for the current search
     */
    @Override
    public void searchRequest(OverviewTable source, MediaSearchParameters params)
    {
        OverviewData overviewData = overviewTables.get(source);
        AbstractOverviewQueryHandler<?> handler =
                overviewData.getOverviewHandler();
        // TODO a search iterator has to be provided
        handler.handleQuery(params, null);
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
     * A handler for clicks on the "create test data" button. This button calls
     * a test server method which creates some dummy entities in the database
     * for the currently logged in user.
     *
     * @param e the click event
     */
    @UiHandler("btnTestData")
    void handleCreateTestDataClick(ClickEvent e)
    {
        MediaSearchServiceAsync searchService =
                GWT.create(MediaSearchService.class);
        btnTestData.setEnabled(false);
        searchService.createTestData(new AsyncCallback<Void>()
        {
            @Override
            public void onFailure(Throwable caught)
            {
                Window.alert("Could not create test data: " + caught);
                btnTestData.setEnabled(true);
            }

            @Override
            public void onSuccess(Void result)
            {
                Window.alert("Test data was successfully created.");
                btnTestData.setEnabled(true);
            }
        });
    }

    /**
     * Initializes the map with query handlers. This method also registers this
     * object as search listener at all overview tables.
     */
    private void initQueryHandlers()
    {
        overviewTables = new HashMap<OverviewTable, OverviewData>();
        overviewTables.put(tabArtists, new OverviewData(new ArtistQueryHandler(
                tabArtists)));
        tabArtists.setSearchListener(this);
        // TODO add further handlers
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
        OverviewData overviewData =
                overviewTables.get(tabPanel.getWidget(index));
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
     * A simple data class for storing information about an overview table.
     * Objects of this class are created for all overview tables managed by
     * {@code RemoteMediaStore}. In addition to storing the handler object for
     * the overview table, this class keeps track if the table has already been
     * initialized.
     */
    private static class OverviewData
    {
        /** Stores the handler for the overview table. */
        private final AbstractOverviewQueryHandler<?> overviewHandler;

        /** A flag whether the table has already been initialized. */
        private boolean initialized;

        /**
         * Creates a new instance of {@code OverviewData} and sets the handler.
         *
         * @param handler the handler for the overview table
         */
        public OverviewData(AbstractOverviewQueryHandler<?> handler)
        {
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
         * this has not been the case, the handler is invoked with an empty
         * search parameters object. This causes a query to be sent to the
         * server. With the results of this query the table is initialized.
         */
        public void ensureInit()
        {
            if (!initialized)
            {
                initialized = true;
                // TODO initialize parameters object properly
                overviewHandler.handleQuery(new MediaSearchParameters(), null);
            }
        }
    }
}
