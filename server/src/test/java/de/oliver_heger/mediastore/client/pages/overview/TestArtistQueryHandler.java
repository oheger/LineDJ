package de.oliver_heger.mediastore.client.pages.overview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.client.I18NFormatter;
import de.oliver_heger.mediastore.client.pages.overview.AbstractOverviewQueryHandler;
import de.oliver_heger.mediastore.client.pages.overview.AbstractResultData;
import de.oliver_heger.mediastore.client.pages.overview.ArtistQueryHandler;
import de.oliver_heger.mediastore.client.pages.overview.ResultData;
import de.oliver_heger.mediastore.client.pages.overview.SearchResultView;
import de.oliver_heger.mediastore.shared.model.ArtistInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * Test class for {@code ArtistQueryHandler}. This class also tests
 * functionality of the base class.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestArtistQueryHandler
{
    /** Constant for the number of test artists. */
    private static final int ARTIST_COUNT = 16;

    /** Constant for the name of a test artist. */
    private static final String ARTIST_NAME = "TestArtist_";

    /** Constant for a client parameter object. */
    private static final Serializable CLIENT_PARAM = Integer.valueOf(111);

    /** Constant for an offset for generated ID values. */
    private static final long ID_OFFSET = 20101128185507L;

    /** Constant for an offset for generated date values. */
    private static final long DATE_OFFSET = 20101128185704L;

    /** Constant for the limit of search results. */
    private static final int LIMIT = 10;

    /** A mock for the view component. */
    private SearchResultView view;

    /** A mock for the search service. */
    private MediaSearchServiceAsync searchService;

    /** The handler to be tested. */
    private ArtistQueryHandlerTestImpl handler;

    @Before
    public void setUp() throws Exception
    {
        view = EasyMock.createMock(SearchResultView.class);
        searchService = EasyMock.createMock(MediaSearchServiceAsync.class);
        handler = new ArtistQueryHandlerTestImpl(view, searchService);
    }

    /**
     * Creates a mock object for a search result.
     *
     * @return the mock
     */
    private static SearchResult<ArtistInfo> createResultMock()
    {
        @SuppressWarnings("unchecked")
        SearchResult<ArtistInfo> result = EasyMock.createMock(SearchResult.class);
        return result;
    }

    /**
     * Creates a search parameters object with default data.
     *
     * @return the parameters object
     */
    private static MediaSearchParameters createSearchParams()
    {
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText("A search text");
        params.setMaxResults(42);
        return params;
    }

    /**
     * Creates a list with test artists. This list can be returned from a mock
     * search result object.
     *
     * @param artistCount the number of artists to add to the list
     * @param artistName
     * @return
     */
    private static List<ArtistInfo> createArtistList(int artistCount)
    {
        List<ArtistInfo> artists = new ArrayList<ArtistInfo>(artistCount);
        for (int i = 0; i < artistCount; i++)
        {
            ArtistInfo a = new ArtistInfo();
            a.setArtistID(Long.valueOf(ID_OFFSET + i));
            a.setName(ARTIST_NAME + i);
            a.setCreationDate(createTestDate(i));
            artists.add(a);
        }
        return artists;
    }

    /**
     * Creates a date for the test artist with the given index.
     *
     * @param i the index
     * @return the created at date for this artist
     */
    private static Date createTestDate(int i)
    {
        return new Date(DATE_OFFSET + i);
    }

    /**
     * Tests whether the correct view is returned.
     */
    @Test
    public void testGetView()
    {
        assertSame("Wrong view", view, handler.getView());
    }

    /**
     * Tests whether a formatter object is available.
     */
    @Test
    public void testGetFormatter()
    {
        assertNotNull("No formatter", handler.getFormatter());
    }

    /**
     * Lets the handler create a result data object based on test data.
     *
     * @return the result data object
     */
    private ResultData fetchResultData()
    {
        SearchResult<ArtistInfo> res = createResultMock();
        List<ArtistInfo> artists = createArtistList(ARTIST_COUNT);
        EasyMock.expect(res.getResults()).andReturn(artists);
        EasyMock.replay(res);
        ResultData data = handler.createResult(res);
        EasyMock.verify(res);
        return data;
    }

    /**
     * Tests whether the result data object created by the handler has the
     * expected rows and columns.
     */
    @Test
    public void testCreateResultDataSizes()
    {
        ResultData data = fetchResultData();
        assertEquals("Wrong number of columns", 2, data.getColumnCount());
        assertEquals("Wrong column name (1)", "Name", data.getColumnName(0));
        assertEquals("Wrong column name (2)", "Created at", data.getColumnName(1));
        assertEquals("Wrong row count", ARTIST_COUNT, data.getRowCount());
    }

    /**
     * Tests whether the result data object has the expected content.
     */
    @Test
    public void testCreateResultDataContent()
    {
        final String fmtPrefix = "<formattedDate>";
        I18NFormatter fmt = new I18NFormatter()
        {
            @Override
            public String formatDate(Date date)
            {
                return fmtPrefix + date;
            }
        };
        handler.installMockFormatter(fmt);
        ResultData data = fetchResultData();
        for (int i = 0; i < ARTIST_COUNT; i++)
        {
            assertEquals("Wrong name at " + i, ARTIST_NAME + i,
                    data.getValueAt(i, 0));
            assertEquals("Wrong date at " + i, fmtPrefix + createTestDate(i),
                    data.getValueAt(i, 1));
        }
    }

    /**
     * Tries to access an invalid property from the result data object.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testCreateResultDataInvalidProperty()
    {
        @SuppressWarnings("unchecked")
        AbstractResultData<ArtistInfo> data =
                (AbstractResultData<ArtistInfo>) fetchResultData();
        data.getPropertyForColumn(data.getDataAt(0), Integer.MAX_VALUE);
    }

    /**
     * Tests whether the result data object returns the expected IDs.
     */
    @Test
    public void testCreateResultDataIDs()
    {
        ResultData data = fetchResultData();
        for (int i = 0; i < ARTIST_COUNT; i++)
        {
            assertEquals("Wrong ID at " + i, Long.valueOf(ID_OFFSET + i),
                    data.getID(i));
        }
    }

    /**
     * Tests whether a search query is correctly initiated.
     */
    @Test
    public void testHandleQuery()
    {
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                handler.initCallbackMock();
        MediaSearchParameters params = createSearchParams();
        searchService.searchArtists(params, sit, callback);
        EasyMock.replay(sit, callback, searchService);
        handler.handleQuery(params, sit);
        EasyMock.verify(sit, callback, searchService);
    }

    /**
     * Helper method for checking the onSuccess() implementation of the query
     * callback for paged results.
     *
     * @param currentPage the index of the current page
     * @param pageCount the total number of pages
     * @param morePages the expected flag whether more results are available
     */
    private void checkQueryCallbackOnSuccessPaged(Integer currentPage,
            Integer pageCount, boolean morePages)
    {
        SearchResult<ArtistInfo> result = createResultMock();
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        ResultData data = handler.initResultDataMock(result);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setClientParameter(CLIENT_PARAM);
        EasyMock.expect(result.getSearchParameters()).andReturn(params);
        EasyMock.expect(result.getSearchIterator()).andReturn(sit);
        EasyMock.expect(sit.hasNext()).andReturn(Boolean.FALSE);
        EasyMock.expect(sit.getCurrentPage()).andReturn(currentPage);
        EasyMock.expect(sit.getPageCount()).andReturn(pageCount);
        view.addSearchResults(data, CLIENT_PARAM);
        view.searchComplete(sit, CLIENT_PARAM, morePages);
        EasyMock.replay(result, sit, data, view, searchService);
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                handler.createQueryCallback(params);
        callback.onSuccess(result);
        EasyMock.verify(result, sit, data, view, searchService);
    }

    /**
     * Tests the onSuccess() method of the query callback for a paged result if
     * the last page is reached.
     */
    @Test
    public void testQueryCallbackOnSuccessPagedNoMorePages()
    {
        checkQueryCallbackOnSuccessPaged(1, 2, false);
    }

    /**
     * Tests the onSuccess() method of the query callback for a paged result if
     * more pages are available.
     */
    @Test
    public void testQueryCallbackOnSuccessPagedMorePages()
    {
        checkQueryCallbackOnSuccessPaged(0, 2, true);
    }

    /**
     * Tests the onSuccess() method of the query callback for a chunk search if
     * no more data is available.
     */
    @Test
    public void testQueryCallbackOnSuccessChunkNoMoreData()
    {
        SearchResult<ArtistInfo> result = createResultMock();
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        ResultData data = handler.initResultDataMock(result);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setClientParameter(CLIENT_PARAM);
        EasyMock.expect(result.getSearchParameters()).andReturn(params);
        EasyMock.expect(result.getSearchIterator()).andReturn(sit);
        EasyMock.expect(sit.hasNext()).andReturn(Boolean.FALSE);
        EasyMock.expect(sit.getCurrentPage()).andReturn(null);
        view.addSearchResults(data, CLIENT_PARAM);
        view.searchComplete(sit, CLIENT_PARAM, false);
        EasyMock.replay(result, sit, data, view, searchService);
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                handler.createQueryCallback(params);
        callback.onSuccess(result);
        EasyMock.verify(result, sit, data, view, searchService);
    }

    /**
     * Helper method for testing onSuccess() of the query callback for a chunk
     * search if more data is available and the limit of search results is not
     * yet reached. This method can be used to test a search with a specific
     * limit or an unlimited search.
     *
     * @param maxResults the original maxResults parameter
     * @param nextMaxResults the maxResults parameter for the next invocation
     * @param artistCount the number of found artists
     */
    private void checkQueryCallbackOnSuccessChunkMoreData(int maxResults,
            int nextMaxResults, int artistCount)
    {
        SearchResult<ArtistInfo> result = createResultMock();
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        ResultData data = handler.initResultDataMock(result);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setClientParameter(CLIENT_PARAM);
        params.setMaxResults(maxResults);
        MediaSearchParameters params2 = new MediaSearchParameters();
        params2.setClientParameter(CLIENT_PARAM);
        params2.setMaxResults(nextMaxResults);
        List<ArtistInfo> artists = createArtistList(artistCount);
        EasyMock.expect(result.getSearchParameters()).andReturn(params);
        EasyMock.expect(result.getSearchIterator()).andReturn(sit);
        EasyMock.expect(result.getResults()).andReturn(artists);
        EasyMock.expect(sit.hasNext()).andReturn(Boolean.TRUE);
        view.addSearchResults(data, CLIENT_PARAM);
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                handler.createQueryCallback(params);
        searchService.searchArtists(params2, sit, callback);
        EasyMock.replay(result, sit, data, view, searchService);
        callback.onSuccess(result);
        EasyMock.verify(result, sit, data, view, searchService);
    }

    /**
     * Tests the onSuccess() method of the query callback for a chunk search if
     * more data is available and the limit of search results is not yet
     * reached.
     */
    @Test
    public void testQueryCallbackOnSuccessChunkMoreData()
    {
        final int artistCount = 3;
        checkQueryCallbackOnSuccessChunkMoreData(LIMIT, LIMIT - artistCount,
                artistCount);
    }

    /**
     * Tests the onSuccess() method of the query callback for a chunk search if
     * more data is available and there is not limit of search results.
     */
    @Test
    public void testQueryCallbackOnSuccessChunkMoreDataUnlimited()
    {
        checkQueryCallbackOnSuccessChunkMoreData(-1, 0, LIMIT);
    }

    /**
     * Tests the onSuccess() method of the query callback for a chunk search if
     * more data is available and the limit of search results has been reached.
     */
    @Test
    public void testQueryCallbackOnSuccessChunkMoreDataLimitReached()
    {
        SearchResult<ArtistInfo> result = createResultMock();
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        ResultData data = handler.initResultDataMock(result);
        final String searchText = "TestSearchText";
        MediaSearchParameters params = new MediaSearchParameters();
        params.setClientParameter(CLIENT_PARAM);
        params.setMaxResults(LIMIT);
        params.setSearchText(searchText);
        MediaSearchParameters params2 = new MediaSearchParameters();
        params2.setMaxResults(1);
        params2.setClientParameter(CLIENT_PARAM);
        params2.setSearchText(searchText);
        List<ArtistInfo> artists = createArtistList(LIMIT);
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                handler.createQueryCallback(params);
        AsyncCallback<SearchResult<ArtistInfo>> moreResultsCb =
                handler.initCallbackMock();
        EasyMock.expect(result.getSearchParameters()).andReturn(params)
                .anyTimes();
        EasyMock.expect(result.getSearchIterator()).andReturn(sit).anyTimes();
        EasyMock.expect(result.getResults()).andReturn(artists);
        EasyMock.expect(sit.hasNext()).andReturn(Boolean.TRUE);
        view.addSearchResults(data, CLIENT_PARAM);
        searchService.searchArtists(params2, sit, moreResultsCb);
        EasyMock.replay(result, sit, data, moreResultsCb, view, searchService);
        callback.onSuccess(result);
        EasyMock.verify(result, sit, data, moreResultsCb, view, searchService);
    }

    /**
     * Creates a callback instance for searching for more results.
     *
     * @param params the search parameters object
     * @param result the search result object
     * @return the callback
     */
    private AsyncCallback<SearchResult<ArtistInfo>> createMoreResultsCb(
            MediaSearchParameters params, SearchResult<ArtistInfo> result)
    {
        AbstractOverviewQueryHandler<ArtistInfo>.QueryCallback cb =
                (AbstractOverviewQueryHandler.QueryCallback) handler
                        .createQueryCallback(params);
        return handler.createMoreResultsCallback(cb, result);
    }

    /**
     * Tests the onSuccess() method of the more results callback if no more data
     * is available.
     */
    @Test
    public void testMoreResultsCallbackOnSuccessNoMoreData()
    {
        SearchResult<ArtistInfo> result = createResultMock();
        SearchResult<ArtistInfo> result2 = createResultMock();
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        SearchIterator sit2 = EasyMock.createMock(SearchIterator.class);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setClientParameter(CLIENT_PARAM);
        EasyMock.expect(result.getSearchIterator()).andReturn(sit);
        EasyMock.expect(result.getSearchParameters()).andReturn(params);
        EasyMock.expect(result2.getResults())
                .andReturn(new ArrayList<ArtistInfo>());
        EasyMock.expect(result2.getSearchIterator()).andReturn(sit2);
        EasyMock.expect(sit2.hasNext()).andReturn(Boolean.FALSE);
        view.searchComplete(sit, CLIENT_PARAM, false);
        EasyMock.replay(result, result2, sit, sit2, view, searchService);
        createMoreResultsCb(params, result).onSuccess(result2);
        EasyMock.verify(result, result2, sit, sit2, view, searchService);
    }

    /**
     * Tests the onSuccess() method of the more results callback if more data is
     * available.
     */
    @Test
    public void testMoreResultsCallbackOnSuccessMoreData()
    {
        SearchResult<ArtistInfo> result = createResultMock();
        SearchResult<ArtistInfo> result2 = createResultMock();
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setClientParameter(CLIENT_PARAM);
        EasyMock.expect(result2.getResults())
                .andReturn(new ArrayList<ArtistInfo>());
        EasyMock.expect(result2.getSearchIterator()).andReturn(sit);
        EasyMock.expect(result2.getSearchParameters()).andReturn(params);
        EasyMock.expect(sit.hasNext()).andReturn(Boolean.TRUE);
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                createMoreResultsCb(params, result);
        searchService.searchArtists(params, sit, callback);
        EasyMock.replay(result, result2, sit, view, searchService);
        callback.onSuccess(result2);
        EasyMock.verify(result, result2, sit, view, searchService);
    }

    /**
     * Tests the onSuccess() method of the more results callback if a result is
     * found.
     */
    @Test
    public void testMoreResultsCallbackOnSuccessGotResult()
    {
        SearchResult<ArtistInfo> result = createResultMock();
        SearchResult<ArtistInfo> result2 = createResultMock();
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setClientParameter(CLIENT_PARAM);
        EasyMock.expect(result.getSearchIterator()).andReturn(sit);
        EasyMock.expect(result.getSearchParameters()).andReturn(params);
        EasyMock.expect(result2.getResults()).andReturn(createArtistList(1));
        view.searchComplete(sit, CLIENT_PARAM, true);
        EasyMock.replay(result, result2, sit, view, searchService);
        createMoreResultsCb(params, result).onSuccess(result2);
        EasyMock.verify(result, result2, sit, view, searchService);
    }

    /**
     * Tests the onFailure() method of the query callback.
     */
    @Test
    public void testQueryCallbackOnFailure()
    {
        Throwable err = new RuntimeException();
        view.onFailure(err, CLIENT_PARAM);
        EasyMock.replay(view, searchService);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setClientParameter(CLIENT_PARAM);
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                handler.createQueryCallback(params);
        callback.onFailure(err);
        EasyMock.verify(view, searchService);
    }

    /**
     * Tests the onFailure() method of the more results callback.
     */
    @Test
    public void testMoreResultsCallbackInFailure()
    {
        SearchResult<ArtistInfo> result = createResultMock();
        Throwable err = new RuntimeException();
        view.onFailure(err, CLIENT_PARAM);
        EasyMock.replay(result, view, searchService);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setClientParameter(CLIENT_PARAM);
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                createMoreResultsCb(params, result);
        callback.onFailure(err);
        EasyMock.verify(result, view, searchService);
    }

    /**
     * A test implementation of the query handler which allows mocking the
     * search service and other functionality.
     */
    private static class ArtistQueryHandlerTestImpl extends ArtistQueryHandler
    {
        /** The mock search service. */
        private final MediaSearchServiceAsync mockSearchService;

        /** A mock callback object. */
        private AsyncCallback<SearchResult<ArtistInfo>> mockCallback;

        /** A mock ResultData object. */
        private ResultData mockResultData;

        /** The expected SearchResult object. */
        private SearchResult<ArtistInfo> expectedSearchResult;

        /** A mock formatter object. */
        private I18NFormatter mockFormat;

        public ArtistQueryHandlerTestImpl(SearchResultView view,
                MediaSearchServiceAsync svc)
        {
            super(view);
            mockSearchService = svc;
        }

        /**
         * Creates and installs a mock callback object.
         *
         * @return the mock object
         */
        @SuppressWarnings("unchecked")
        public AsyncCallback<SearchResult<ArtistInfo>> initCallbackMock()
        {
            mockCallback = EasyMock.createMock(AsyncCallback.class);
            return mockCallback;
        }

        /**
         * Initializes a mock ResultData object.
         *
         * @param expSR the expected search result object
         * @return the mock result data object
         */
        public ResultData initResultDataMock(SearchResult<ArtistInfo> expSR)
        {
            mockResultData = EasyMock.createMock(ResultData.class);
            expectedSearchResult = expSR;
            return mockResultData;
        }

        /**
         * Sets a mock formatter to be returned by getFormatter().
         *
         * @param fmt the mock formatter
         */
        public void installMockFormatter(I18NFormatter fmt)
        {
            mockFormat = fmt;
        }

        /**
         * Either returns the mock search service or calls the super method.
         */
        @Override
        public MediaSearchServiceAsync getSearchService()
        {
            return (mockSearchService != null) ? mockSearchService : super
                    .getSearchService();
        }

        /**
         * Either returns the mock result data object or calls the super method.
         */
        @Override
        protected ResultData createResult(SearchResult<ArtistInfo> result)
        {
            if (mockResultData != null)
            {
                assertSame("Wrong search result", expectedSearchResult, result);
                return mockResultData;
            }
            return super.createResult(result);
        }

        /**
         * Either returns the mock callback or calls the super method.
         */
        @Override
        protected AsyncCallback<SearchResult<ArtistInfo>> createQueryCallback(
                MediaSearchParameters params)
        {
            return (mockCallback != null) ? mockCallback : super
                    .createQueryCallback(params);
        }

        /**
         * Either returns the mock formatter or calls the super method.
         */
        @Override
        protected I18NFormatter getFormatter()
        {
            return (mockFormat != null) ? mockFormat : super.getFormatter();
        }

        /**
         * Either returns the mock callback or calls the super method.
         */
        @Override
        AsyncCallback<SearchResult<ArtistInfo>> createMoreResultsCallback(
                QueryCallback parent, SearchResult<ArtistInfo> result)
        {
            return (mockCallback != null) ? mockCallback : super
                    .createMoreResultsCallback(parent, result);
        }
    }
}
