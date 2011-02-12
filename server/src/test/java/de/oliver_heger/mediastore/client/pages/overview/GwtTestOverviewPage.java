package de.oliver_heger.mediastore.client.pages.overview;

import com.google.gwt.event.logical.shared.SelectionEvent;
import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.client.pageman.PageManager;
import de.oliver_heger.mediastore.client.pages.MockPageManager;
import de.oliver_heger.mediastore.client.pages.Pages;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * Test class for {@code OverviewPage}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class GwtTestOverviewPage extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Creates a page manager instance.
     *
     * @return the page manager
     */
    private static MockPageManager createPageManager()
    {
        return new MockPageManager();
    }

    /**
     * Tests whether all fields are properly set by the UI binder.
     */
    public void testInit()
    {
        OverviewPage page = new OverviewPage();
        assertNotNull("No tab panel", page.tabPanel);
        assertNotNull("No artist table", page.tabArtists);
        assertNotNull("No songs table", page.tabSongs);
        assertNotNull("No albums table", page.tabAlbums);
    }

    /**
     * Tests whether initialization of the component works as expected.
     */
    public void testInitialize()
    {
        OverviewPage page = new OverviewPage();
        PageManager pm = createPageManager();
        page.initialize(pm);
        assertSame("Page manager not set", pm, page.getPageManager());
        AbstractOverviewQueryHandler<?> handler =
                page.fetchQueryHandler(page.tabArtists);
        assertNotNull("Handlers not initialized", handler);
        assertNotNull("No image resources", page.getImageResources());
    }

    /**
     * Helper method for testing whether a specific open page handler has been
     * registered at an overview table.
     *
     * @param overview the overview page
     * @param tab the overview table
     * @param page the target page
     */
    private void checkPageHandler(OverviewPage overview, OverviewTable tab,
            Pages page)
    {
        PageManager pm = createPageManager();
        overview.initialize(pm);
        boolean found = false;
        for (SingleElementHandler h : tab.getSingleElementHandlers())
        {
            if (h instanceof OpenPageSingleElementHandler)
            {
                OpenPageSingleElementHandler oph =
                        (OpenPageSingleElementHandler) h;
                assertSame("Wrong page manager", pm, oph.getPageManager());
                if (oph.getPage() == page)
                {
                    found = true;
                    break;
                }
            }
        }
        assertTrue("No handler found for page", found);
    }

    /**
     * Tests whether a handler for the details of an artist has been installed.
     */
    public void testArtistDetailsHandler()
    {
        OverviewPage page = new OverviewPage();
        checkPageHandler(page, page.tabArtists, Pages.ARTISTDETAILS);
    }

    /**
     * Tests whether a handler for details of a song has been installed.
     */
    public void testSongDetailsHandler()
    {
        OverviewPage page = new OverviewPage();
        checkPageHandler(page, page.tabSongs, Pages.SONGDETAILS);
    }

    /**
     * Tests whether a handler for the details of an album has been installed.
     */
    public void testAlbumDetailsHandler()
    {
        OverviewPage page = new OverviewPage();
        checkPageHandler(page, page.tabAlbums, Pages.ALBUMDETAILS);
    }

    /**
     * Tests whether a query handler for artists can be obtained.
     */
    public void testFetchArtistQueryHandler()
    {
        OverviewPage page = new OverviewPage();
        page.initQueryHandlers();
        AbstractOverviewQueryHandler<?> handler =
                page.fetchQueryHandler(page.tabArtists);
        assertTrue("Wrong query handler", handler instanceof ArtistQueryHandler);
        assertSame("Wrong search listener", page,
                page.tabArtists.getSearchListener());
    }

    /**
     * Tests whether a query handler for songs can be obtained.
     */
    public void testFetchSongQueryHandler()
    {
        OverviewPage page = new OverviewPage();
        page.initQueryHandlers();
        AbstractOverviewQueryHandler<?> handler =
                page.fetchQueryHandler(page.tabSongs);
        assertTrue("Wrong query handler", handler instanceof SongQueryHandler);
        assertSame("Wrong search listener", page,
                page.tabSongs.getSearchListener());
    }

    /**
     * Tests whether a query handler for albums can be obtained.
     */
    public void testFetchAlbumQueryHandler()
    {
        OverviewPage page = new OverviewPage();
        page.initQueryHandlers();
        AbstractOverviewQueryHandler<?> handler =
                page.fetchQueryHandler(page.tabAlbums);
        assertTrue("Wrong query handler", handler instanceof AlbumQueryHandler);
        assertSame("Wrong search listener", page,
                page.tabAlbums.getSearchListener());
    }

    /**
     * Tests whether a search request is correctly processed.
     */
    public void testSearchRequest()
    {
        final OverviewTable view = new OverviewTable();
        final QueryHandlerTestImpl handler = new QueryHandlerTestImpl(view);
        OverviewPage page = new OverviewPage()
        {
            @Override
            AbstractOverviewQueryHandler<?> fetchQueryHandler(
                    OverviewTable table)
            {
                assertSame("Wrong view", view, table);
                return handler;
            }
        };
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText("testSearchText");
        page.searchRequest(view, params);
        handler.verifyHandleQuery(params, null);
    }

    /**
     * Helper method for testing whether an overview page is initialized when
     * its tab is activated for the first time.
     *
     * @return the page used by the test
     */
    private OverviewPage checkTabSelectionChanged()
    {
        final QueryHandlerTestImpl handler =
                new QueryHandlerTestImpl(new OverviewTable());
        OverviewPage page = new OverviewPage()
        {
            @Override
            protected AbstractOverviewQueryHandler<?> createArtistQueryHandler()
            {
                return handler;
            }
        };
        page.initQueryHandlers();
        SelectionEvent.fire(page.tabPanel, 0);
        handler.verifyHandleQuery(new MediaSearchParameters(), null);
        return page;
    }

    /**
     * Tests whether an overview page is initialized when its tab is activated
     * for the first time.
     */
    public void testTabSelectionChanged()
    {
        checkTabSelectionChanged();
    }

    /**
     * Tests whether an initialization of an overview table is only performed at
     * the first access.
     */
    public void testTabSelectionChangedMultipleTimes()
    {
        OverviewPage page = checkTabSelectionChanged();
        // The following would cause an exception if another query is issued.
        SelectionEvent.fire(page.tabPanel, 0);
    }

    /**
     * A test implementation of a query handler that provides mocking
     * facilities.
     */
    private static class QueryHandlerTestImpl extends
            AbstractOverviewQueryHandler<Object>
    {
        /** Stores the passed in search parameters object. */
        private MediaSearchParameters queryParameters;

        /** Stores the passed in search iterator. */
        private SearchIterator queryIterator;

        public QueryHandlerTestImpl(SearchResultView v)
        {
            super(v);
        }

        /**
         * Checks whether handleQuery() was called with the expected parameters.
         *
         * @param expParams the expected search parameters
         * @param expIt the expected search iterator
         */
        public void verifyHandleQuery(MediaSearchParameters expParams,
                SearchIterator expIt)
        {
            assertEquals("Wrong search parameters", expParams, queryParameters);
            assertEquals("Wrong search iterator", expIt, queryIterator);
        }

        /**
         * Records this invocation.
         */
        @Override
        public void handleQuery(MediaSearchParameters searchParams,
                SearchIterator searchIterator)
        {
            assertNull("Too many invocations", queryParameters);
            queryParameters = searchParams;
            queryIterator = searchIterator;
        }

        @Override
        protected void callService(MediaSearchServiceAsync service,
                MediaSearchParameters searchParams,
                SearchIterator searchIterator,
                AsyncCallback<SearchResult<Object>> callback)
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        protected ResultData createResult(SearchResult<Object> result)
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }
    }
}
