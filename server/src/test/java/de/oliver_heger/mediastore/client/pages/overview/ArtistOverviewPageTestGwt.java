package de.oliver_heger.mediastore.client.pages.overview;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import com.google.gwt.cell.client.CheckboxCell;
import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.view.client.MultiSelectionModel;
import com.google.gwt.view.client.ProvidesKey;
import com.google.gwt.view.client.SelectionModel;

import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.shared.model.AlbumInfo;
import de.oliver_heger.mediastore.shared.model.ArtistInfo;
import de.oliver_heger.mediastore.shared.model.SongInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * Test class for {@code ArtistOverviewPage}. This class also tests
 * functionality of the base overview table class.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class ArtistOverviewPageTestGwt extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Tests whether the correct query handler has been set.
     */
    public void testInitQueryHandler()
    {
        ArtistOverviewPage page = new ArtistOverviewPage();
        assertTrue("Wrong query handler",
                page.getQueryHandler() instanceof ArtistOverviewQueryHandler);
    }

    /**
     * Tests whether a correct key provider has been set.
     */
    public void testInitKeyProvider()
    {
        ArtistOverviewPage page = new ArtistOverviewPage();
        ProvidesKey<ArtistInfo> keyProvider = page.cellTable.getKeyProvider();
        checkKeyProvider(keyProvider);
    }

    /**
     * Helper method for testing a key provider for artists.
     *
     * @param keyProvider the key provider to be tested
     */
    private void checkKeyProvider(ProvidesKey<? super ArtistInfo> keyProvider)
    {
        ArtistInfo info = new ArtistInfo();
        info.setArtistID(20111007220914L);
        assertEquals("Wrong key", info.getArtistID(), keyProvider.getKey(info));
    }

    /**
     * Tests whether the search service can be accessed.
     */
    public void testGetSearchService()
    {
        ArtistOverviewPage page = new ArtistOverviewPage();
        MediaSearchServiceAsync service = page.getSearchService();
        assertNotNull("No search service", service);
        assertSame("Multiple service instances", service,
                page.getSearchService());
    }

    /**
     * Tests whether the initialization of the page works as expected.
     * Especially the data provider has to be associated with the cell widget
     * and called once.
     */
    public void testInitialize()
    {
        ArtistOverviewPageTestImpl page = new ArtistOverviewPageTestImpl();
        MockPageManager pm = new MockPageManager();
        page.initialize(pm);
        assertSame("Page manager not set", pm, page.getPageManager());
        assertTrue("Too few columns in table",
                page.cellTable.getColumnCount() > 1);
        page.getSearchService().checkNumberOfRequests(1);
        assertNull("Got a search text", page.getSearchService().nextRequest()
                .getSearchText());
    }

    /**
     * Tests the column for the artist's name.
     */
    public void testCreateArtistNameColumn()
    {
        ArtistOverviewPage page = new ArtistOverviewPage();
        Column<ArtistInfo, String> col = page.createArtistNameColumn();
        assertTrue("Not a link column", col instanceof LinkColumn<?>);
        ArtistInfo info = new ArtistInfo();
        info.setName("Pink Floyd");
        info.setArtistID(20111008173410L);
        assertEquals("Wrong value", info.getName(), col.getValue(info));
        LinkColumn<ArtistInfo> lcol = (LinkColumn<ArtistInfo>) col;
        assertEquals("Wrong ID", info.getArtistID(), lcol.getID(info));
    }

    /**
     * Tests the column for the creation date.
     */
    public void testCreateDateColumn()
    {
        ArtistOverviewPage page = new ArtistOverviewPage();
        Column<ArtistInfo, String> col = page.createDateColumn();
        ArtistInfo info = new ArtistInfo();
        info.setCreationDate(new Date(20111011081556L));
        assertEquals("Wrong value",
                page.getFormatter().formatDate(info.getCreationDate()),
                col.getValue(info));
    }

    /**
     * Creates and initializes a test page.
     *
     * @return the test page
     */
    private ArtistOverviewPageTestImpl createInitializedPage()
    {
        ArtistOverviewPageTestImpl page = new ArtistOverviewPageTestImpl();
        page.initialize(new MockPageManager());
        return page;
    }

    /**
     * Tests whether the data provider was correctly initialized.
     */
    public void testInitDataProvider()
    {
        ArtistOverviewPageTestImpl page = createInitializedPage();
        OverviewDataProvider<ArtistInfo> dataProvider = page.getDataProvider();
        OverviewCallbackFactoryImpl<?> factory =
                (OverviewCallbackFactoryImpl<?>) dataProvider
                        .getCallbackFactory();
        assertEquals("Wrong error indicator", page.pnlError,
                factory.getErrorIndicator());
    }

    /**
     * Obtains the test search service and skips the initial search request.
     *
     * @param page the test page
     * @return the test search service
     */
    private SearchServiceTestImpl fetchSearchServiceAndSkipInitialRequest(
            ArtistOverviewPageTestImpl page)
    {
        SearchServiceTestImpl searchService = page.getSearchService();
        searchService.checkNumberOfRequests(2);
        searchService.nextRequest(); // skip first
        return searchService;
    }

    /**
     * Tests a click on the refresh() button.
     */
    public void testHandleRefreshClick()
    {
        ArtistOverviewPageTestImpl page = createInitializedPage();
        page.txtSearch.setText("test search");
        page.handleRefreshClick(null);
        SearchServiceTestImpl searchService =
                fetchSearchServiceAndSkipInitialRequest(page);
        MediaSearchParameters params = searchService.nextRequest();
        assertNull("Got a search text", params.getSearchText());
        assertEquals("Wrong first position", 0, params.getFirstResult());
    }

    /**
     * Tests a click on the search button.
     */
    public void testHandleSearchClick()
    {
        ArtistOverviewPageTestImpl page = createInitializedPage();
        final String searchText = "Floyd";
        page.txtSearch.setText(searchText);
        page.handleSearchClick(null);
        SearchServiceTestImpl service =
                fetchSearchServiceAndSkipInitialRequest(page);
        MediaSearchParameters params = service.nextRequest();
        assertEquals("Wrong search text", searchText, params.getSearchText());
        assertEquals("Wrong first position", 0, params.getFirstResult());
    }

    /**
     * Tests the selection model created for the cell table.
     */
    public void testSelectionModel()
    {
        ArtistOverviewPage page = createInitializedPage();
        SelectionModel<? super ArtistInfo> selectionModel =
                page.cellTable.getSelectionModel();
        assertTrue("No multi selection",
                selectionModel instanceof MultiSelectionModel);
        checkKeyProvider(selectionModel);
    }

    /**
     * Tests whether a column for the row selection has been added.
     */
    public void testSelectionColumn()
    {
        ArtistOverviewPage page = createInitializedPage();
        Column<ArtistInfo, ?> column = page.cellTable.getColumn(0);
        assertTrue("Wrong cell", column.getCell() instanceof CheckboxCell);
    }

    /**
     * Tests whether the selection column is created correctly.
     */
    public void testCreateSelectionColumn()
    {
        ArtistOverviewPage page = new ArtistOverviewPage();
        Column<ArtistInfo, Boolean> column = page.createSelectionColumn();
        @SuppressWarnings("unchecked")
        MultiSelectionModel<ArtistInfo> model =
                (MultiSelectionModel<ArtistInfo>) page.cellTable
                        .getSelectionModel();
        ArtistInfo info = new ArtistInfo();
        info.setArtistID(20111012222856L);
        assertEquals("Wrong value for not selected", Boolean.FALSE,
                column.getValue(info));
        model.setSelected(info, true);
        assertEquals("Wrong value for selected", Boolean.TRUE,
                column.getValue(info));
    }

    /**
     * A special search service mock implementation.
     */
    private static class SearchServiceTestImpl implements
            MediaSearchServiceAsync
    {
        /** A list with the search parameters passed to this service. */
        private final List<MediaSearchParameters> searchParameters =
                new LinkedList<MediaSearchParameters>();

        /**
         * Checks whether the expected number of search requests was received.
         *
         * @param expected the expected number of requests
         */
        public void checkNumberOfRequests(int expected)
        {
            assertEquals("Wrong number of requests", expected,
                    searchParameters.size());
        }

        /**
         * Returns the next search request that was received during test
         * execution.
         *
         * @return the next search request
         */
        public MediaSearchParameters nextRequest()
        {
            return searchParameters.remove(0);
        }

        /**
         * Records this method invocation.
         */
        @Override
        public void searchArtists(MediaSearchParameters params,
                SearchIterator iterator,
                AsyncCallback<SearchResult<ArtistInfo>> callback)
        {
            assertNull("Got a search iterator", iterator);
            assertNotNull("No callback", callback);
            searchParameters.add(params);
        }

        @Override
        public void searchSongs(MediaSearchParameters params,
                SearchIterator iterator,
                AsyncCallback<SearchResult<SongInfo>> callback)
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public void searchAlbums(MediaSearchParameters params,
                SearchIterator iterator,
                AsyncCallback<SearchResult<AlbumInfo>> callback)
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public void createTestData(AsyncCallback<Void> callback)
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }
    }

    /**
     * A test implementation of the overview page. This implementation uses a
     * mock search service.
     */
    private static class ArtistOverviewPageTestImpl extends ArtistOverviewPage
    {
        /** The mock search service. */
        private final SearchServiceTestImpl searchService =
                new SearchServiceTestImpl();

        @Override
        protected SearchServiceTestImpl getSearchService()
        {
            return searchService;
        }
    }
}
