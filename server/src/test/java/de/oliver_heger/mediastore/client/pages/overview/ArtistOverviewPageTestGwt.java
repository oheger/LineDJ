package de.oliver_heger.mediastore.client.pages.overview;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import com.google.gwt.cell.client.CheckboxCell;
import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.DomEvent;
import com.google.gwt.event.shared.HasHandlers;
import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.cellview.client.ColumnSortList;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.PushButton;
import com.google.gwt.view.client.MultiSelectionModel;
import com.google.gwt.view.client.ProvidesKey;
import com.google.gwt.view.client.SelectionChangeEvent;
import com.google.gwt.view.client.SelectionModel;

import de.oliver_heger.mediastore.client.ImageResources;
import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.shared.model.AlbumInfo;
import de.oliver_heger.mediastore.shared.model.ArtistInfo;
import de.oliver_heger.mediastore.shared.model.SongInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.OrderDef;
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
    /** Constant for a label for multiple element handlers. */
    private static final String LABEL = "Action_";

    /** Constant for the number of test element handlers. */
    private static final int HANDLER_COUNT = 4;

    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Creates a test artist info instance with a given ID.
     *
     * @param id the ID of the artist
     * @return the test instance
     */
    private static ArtistInfo createArtistInfo(Long id)
    {
        ArtistInfo info = new ArtistInfo();
        info.setArtistID(id);
        return info;
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
        ArtistInfo info = createArtistInfo(20111007220914L);
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
        ArtistInfo info = createArtistInfo(20111008173410L);
        info.setName("Pink Floyd");
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
        assertSame("Wrong order provider", page,
                dataProvider.getOrderDefinitionProvider());
    }

    /**
     * Obtains the test search service and skips and number of requests.
     * @param page the test page
     * @param skip the number of initial requests to skip
     * @return the test search service
     */
    private SearchServiceTestImpl fetchSearchServiceAndSkipRequests(ArtistOverviewPageTestImpl page, int skip)
    {
        SearchServiceTestImpl searchService = page.getSearchService();
        searchService.checkNumberOfRequests(1+skip);
        for(int i = 0; i < skip; i++) {
        searchService.nextRequest();
        }
        return searchService;
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
        return fetchSearchServiceAndSkipRequests(page, 1);
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
     * Tests whether a request is enforced by a click of the refresh button.
     */
    public void testHandleRefreshClickForceRequest()
    {
        ArtistOverviewPageTestImpl page = createInitializedPage();
        page.txtSearch.setText("test search");
        page.handleSearchClick(null);
        page.handleRefreshClick(null);
        fetchSearchServiceAndSkipRequests(page, 2);
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
     * Tests whether selection is cleared on a refresh operation.
     */
    public void testClearSelectionOnRefresh()
    {
        ArtistOverviewPage page = createInitializedPage();
        ArtistInfo info = createArtistInfo(20111015173640L);
        SelectionModel<? super ArtistInfo> selectionModel =
                page.cellTable.getSelectionModel();
        selectionModel.setSelected(info, true);
        page.refresh();
        assertFalse("Still selected", selectionModel.isSelected(info));
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
        ArtistInfo info = createArtistInfo(20111012222856L);
        assertEquals("Wrong value for not selected", Boolean.FALSE,
                column.getValue(info));
        model.setSelected(info, true);
        assertEquals("Wrong value for selected", Boolean.TRUE,
                column.getValue(info));
    }

    /**
     * Adds a number of test handlers for multiple elements.
     *
     * @param table the target table
     * @return an array with the handlers that have been added
     */
    private MultiElementHandlerTestImpl[] addMultiElementHandlers(
            AbstractOverviewTable<?> table)
    {
        MultiElementHandlerTestImpl[] result =
                new MultiElementHandlerTestImpl[HANDLER_COUNT];
        ImageResources res = GWT.create(ImageResources.class);
        for (int i = 0; i < HANDLER_COUNT; i++)
        {
            result[i] = new MultiElementHandlerTestImpl();
            table.addMultiElementHandler(res.removeItem(), LABEL + i, result[i]);
        }
        return result;
    }

    /**
     * Retrieves the button for the multiple element handler with the given
     * index.
     *
     * @param table the table
     * @param i the index
     * @return the button associated with this multiple element handler
     */
    private PushButton fetchMultiHandlerButton(AbstractOverviewTable<?> table,
            int i)
    {
        return (PushButton) table.pnlMultiHandlers.getWidget(i);
    }

    /**
     * Tests whether multiple element handlers can be added.
     */
    public void testAddMultiElementHandler()
    {
        ArtistOverviewPage page = new ArtistOverviewPage();
        addMultiElementHandlers(page);
        assertEquals("Wrong number of buttons", HANDLER_COUNT,
                page.pnlMultiHandlers.getWidgetCount());
        for (int i = 0; i < HANDLER_COUNT; i++)
        {
            PushButton btn = fetchMultiHandlerButton(page, i);
            assertEquals("Wrong label", LABEL + i, btn.getText());
            assertFalse("Enabled", btn.isEnabled());
        }
    }

    /**
     * Helper method for checking the enabled state of the table's multiple
     * element handlers.
     *
     * @param table the overview table
     * @param expFlag the expected enabled flag
     */
    private void checkMultiHandlerEnabled(AbstractOverviewTable<?> table,
            boolean expFlag)
    {
        for (int i = 0; i < HANDLER_COUNT; i++)
        {
            assertEquals("Wrong enabled flag at " + i, expFlag,
                    fetchMultiHandlerButton(table, i).isEnabled());
        }
    }

    /**
     * Tests whether the buttons for multiple element handlers are enabled or
     * disabled based on the current selection.
     */
    public void testMultiElementHandlersEnabled()
    {
        ArtistOverviewPage page = new ArtistOverviewPage();
        addMultiElementHandlers(page);
        SelectionChangeEvent.Handler handler =
                page.createSelectionChangeHandler();
        SelectionModel<? super ArtistInfo> model =
                page.cellTable.getSelectionModel();
        checkMultiHandlerEnabled(page, false);
        ArtistInfo info = createArtistInfo(20111014212420L);
        model.setSelected(info, true);
        handler.onSelectionChange(null);
        checkMultiHandlerEnabled(page, true);
        model.setSelected(info, false);
        handler.onSelectionChange(null);
        checkMultiHandlerEnabled(page, false);
    }

    /**
     * Helper method for firing a click event on a widget.
     *
     * @param btn the widget
     */
    private static void fireClickEvent(HasHandlers btn)
    {
        NativeEvent clickEvent =
                Document.get().createClickEvent(0, 0, 0, 0, 0, false, false,
                        false, false);
        DomEvent.fireNativeEvent(clickEvent, btn);
    }

    /**
     * Tests whether handlers for multiple elements are correctly invoked.
     */
    public void testMultiElementHandlerExecute()
    {
        ArtistOverviewPage table = new ArtistOverviewPage();
        MultiElementHandlerTestImpl[] handlers = addMultiElementHandlers(table);
        List<ArtistInfo> selArtists = new ArrayList<ArtistInfo>();
        selArtists.add(createArtistInfo(20111015174921L));
        selArtists.add(createArtistInfo(20111015174946L));
        selArtists.add(createArtistInfo(20111015175001L));
        for (ArtistInfo info : selArtists)
        {
            table.cellTable.getSelectionModel().setSelected(info, true);
        }
        fireClickEvent(fetchMultiHandlerButton(table, 0));
        assertEquals("Wrong number of IDs", selArtists.size(), handlers[0]
                .getElementIDs().size());
        for (ArtistInfo info : selArtists)
        {
            assertTrue("Element ID not found: " + info, handlers[0]
                    .getElementIDs().contains(info.getArtistID()));
        }
    }

    /**
     * Tests whether a handler for removing elements has been installed.
     */
    public void testRemoveElementHandler()
    {
        ArtistOverviewPage page = createInitializedPage();
        RemoveElementHandler handler = null;
        for (MultiElementHandler meh : page.getMultiElementHandlers())
        {
            if (meh instanceof RemoveElementHandler)
            {
                assertNull("Got multiple remove handlers", handler);
                handler = (RemoveElementHandler) meh;
            }
        }
        assertNotNull("No remove handler found", handler);
        assertEquals("Wrong control", page, handler.getInitiatingControl());
        assertSame("Wrong remove controller", page.getRemoveController(),
                handler.getRemoveController());
        assertNotNull("No remove controller", handler.getRemoveController());
        assertEquals("Wrong service handler class", page
                .createRemoveArtistHandler().getClass(), handler
                .getServiceHandler().getClass());
    }

    /**
     * Tests the service handler for removing artists.
     */
    public void testRemoveArtistServiceHandler()
    {
        ArtistOverviewPage page = new ArtistOverviewPage();
        RemoveServiceHandler handler = page.createRemoveArtistHandler();
        RemoveMediaServiceMock service = new RemoveMediaServiceMock();
        final Long elemID = 20111018080335L;
        handler.removeElement(service, elemID,
                RemoveMediaServiceMock.getRemoveCallback());
        assertEquals("Wrong removed artist", elemID,
                service.getRemoveArtistID());
    }

    /**
     * Helper method for checking the content of an order definition.
     *
     * @param defs the list with all order definition objects
     * @param idx the index of the object to check
     * @param field the expected field name
     * @param descending the expected descending flag
     */
    private static void checkOrderDef(List<OrderDef> defs, int idx,
            String field, boolean descending)
    {
        OrderDef od = defs.get(idx);
        assertEquals("Wrong order definition", field, od.getFieldName());
        assertEquals("Wrong descending flag", descending, od.isDescending());
    }

    /**
     * Tests the default order definition set for the artist table.
     */
    public void testGetOrderDefinitionDefault()
    {
        ArtistOverviewPageTestImpl page = createInitializedPage();
        List<OrderDef> defs = page.getOrderDefinitions();
        assertEquals("Wrong number of definitions", 1, defs.size());
        checkOrderDef(defs, 0, "searchName", false);
    }

    /**
     * Tests whether a specific order definition can be retrieved.
     */
    public void testGetOrderDefinitionSpecific()
    {
        ArtistOverviewPageTestImpl page = createInitializedPage();
        ColumnSortList sortList = page.cellTable.getColumnSortList();
        sortList.clear();
        sortList.push(new ColumnSortList.ColumnSortInfo(page.cellTable
                .getColumn(2), false));
        List<OrderDef> defs = page.getOrderDefinitions();
        assertEquals("Wrong number of definitions", 1, defs.size());
        checkOrderDef(defs, 0, "creationDate", true);
    }

    /**
     * Tests whether only a single order definition is evaluated.
     */
    public void testGetOrderDefinitionMulti()
    {
        ArtistOverviewPageTestImpl page = createInitializedPage();
        ColumnSortList sortList = page.cellTable.getColumnSortList();
        sortList.clear();
        sortList.push(new ColumnSortList.ColumnSortInfo(page.cellTable
                .getColumn(2), false));
        sortList.push(page.cellTable.getColumn(1));
        List<OrderDef> defs = page.getOrderDefinitions();
        assertEquals("Wrong number of definitions", 1, defs.size());
        checkOrderDef(defs, 0, "searchName", false);
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

    /**
     * A tests implementation of a multiple element handler which is used to
     * check whether the expected IDs are passed.
     */
    private static class MultiElementHandlerTestImpl implements
            MultiElementHandler
    {
        /** Stores the passed in element IDs. */
        private Set<Object> ids;

        /**
         * Returns the set with the IDs that was passed to this handler.
         *
         * @return the set with element IDs
         */
        public Set<Object> getElementIDs()
        {
            return ids;
        }

        /**
         * Records this invocation and stores the IDs.
         */
        @Override
        public void handleElements(Set<Object> elemIDs)
        {
            ids = elemIDs;
        }
    }
}
