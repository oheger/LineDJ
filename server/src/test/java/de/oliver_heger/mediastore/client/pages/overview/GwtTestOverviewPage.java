package de.oliver_heger.mediastore.client.pages.overview;

import com.google.gwt.event.logical.shared.SelectionEvent;
import com.google.gwt.junit.client.GWTTestCase;
import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.client.BasicMediaServiceTestImpl;
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
    /** Constant for a stub callback for remove operations. */
    private static final AsyncCallback<Boolean> REMOVE_CALLBACK =
            new AsyncCallback<Boolean>()
            {
                @Override
                public void onFailure(Throwable caught)
                {
                    throw new UnsupportedOperationException(
                            "Unexpected method call!");
                }

                @Override
                public void onSuccess(Boolean result)
                {
                    throw new UnsupportedOperationException(
                            "Unexpected method call!");
                }
            };

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
     * Searches the specified table for a single element handler for removing an
     * element.
     *
     * @param tab the overview table
     * @return the handler found
     */
    private RemoveSingleElementHandler findRemoveHandler(OverviewTable tab)
    {
        for (SingleElementHandler h : tab.getSingleElementHandlers())
        {
            if (h instanceof RemoveSingleElementHandler)
            {
                RemoveSingleElementHandler rh = (RemoveSingleElementHandler) h;
                assertSame("Wrong overview table", tab, rh.getOverviewTable());
                return rh;
            }
        }
        fail("No remove handler installed!");
        return null;
    }

    /**
     * Tests whether the single element handler for removing an artist has been
     * correctly installed.
     */
    public void testRemoveArtistSingleHandler()
    {
        OverviewPage page = new OverviewPage();
        page.initialize(createPageManager());
        RemoveMediaServiceMock service = new RemoveMediaServiceMock();
        RemoveSingleElementHandler handler = findRemoveHandler(page.tabArtists);
        final Long artistID = 20110522183023L;
        handler.getServiceHandler().removeElement(service, artistID,
                REMOVE_CALLBACK);
        assertEquals("Wrong artist ID", artistID, service.getRemoveArtistID());
    }

    /**
     * Tests whether the single element handler for removing an album has been
     * correctly installed.
     */
    public void testRemoveAlbumSingleHandler()
    {
        OverviewPage page = new OverviewPage();
        page.initialize(createPageManager());
        RemoveMediaServiceMock service = new RemoveMediaServiceMock();
        RemoveSingleElementHandler handler = findRemoveHandler(page.tabAlbums);
        final Long albumID = 20110522221401L;
        handler.getServiceHandler().removeElement(service, albumID,
                REMOVE_CALLBACK);
        assertEquals("Wrong album ID", albumID, service.getRemoveAlbumID());
    }

    /**
     * Tests whether the single element handler for removing a song has been
     * correctly installed.
     */
    public void testRemoveSongSingleHandler()
    {
        OverviewPage page = new OverviewPage();
        page.initialize(createPageManager());
        RemoveMediaServiceMock service = new RemoveMediaServiceMock();
        RemoveSingleElementHandler handler = findRemoveHandler(page.tabSongs);
        final String songID = "SONG_" + 20110522221610L;
        handler.getServiceHandler().removeElement(service, songID,
                REMOVE_CALLBACK);
        assertEquals("Wrong song ID", songID, service.getRemoveSongID());
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

    /**
     * A mock service implementation which allows mocking the remove methods.
     */
    private static class RemoveMediaServiceMock extends
            BasicMediaServiceTestImpl
    {
        /** The ID of the artist to be removed. */
        private Long removeArtistID;

        /** The ID of the album to be removed. */
        private Long removeAlbumID;

        /** The ID of the song to be removed. */
        private String removeSongID;

        /**
         * Returns the ID of the artist to be removed.
         *
         * @return the artist ID
         */
        public Long getRemoveArtistID()
        {
            return removeArtistID;
        }

        /**
         * Returns the ID of the album to be removed.
         *
         * @return the album ID
         */
        public Long getRemoveAlbumID()
        {
            return removeAlbumID;
        }

        /**
         * Returns the ID of the song to be removed.
         *
         * @return the song ID
         */
        public String getRemoveSongID()
        {
            return removeSongID;
        }

        @Override
        public void removeArtist(long artistID, AsyncCallback<Boolean> callback)
        {
            checkRemoveInvocation(callback);
            removeArtistID = artistID;
        }

        @Override
        public void removeSong(String songID, AsyncCallback<Boolean> callback)
        {
            checkRemoveInvocation(callback);
            removeSongID = songID;
        }

        @Override
        public void removeAlbum(long albumID, AsyncCallback<Boolean> callback)
        {
            checkRemoveInvocation(callback);
            removeAlbumID = albumID;
        }

        /**
         * Checks whether a remove invocation is valid. The callback is checked
         * and whether no other element has been removed before.
         *
         * @param callback the callback
         */
        private void checkRemoveInvocation(AsyncCallback<Boolean> callback)
        {
            assertNull("Already an artist removed", removeArtistID);
            assertNull("Already an album removed", removeAlbumID);
            assertNull("Already a song removed", removeSongID);
            assertSame("Wrong callback", REMOVE_CALLBACK, callback);
        }
    }
}
