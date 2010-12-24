package de.oliver_heger.mediastore.client.pages.detail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.model.ArtistInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * Test class for {@code ArtistSynonymQueryHandler}. This class also tests
 * functionality of the abstract base class.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestArtistSynonymQueryHandler
{
    /** Constant for the search text. */
    private static final String SEARCH_TEXT = "TestSearchText";

    /** Constant for a client parameter. */
    private static final Long CLIENT_PARAM = 20101224203131L;

    /** A mock for the search service. */
    private MediaSearchServiceAsync service;

    /** A mock for the results view. */
    private SynonymSearchResultView view;

    /** The handler to be tested. */
    private ArtistSynonymQueryHandlerTestImpl handler;

    @Before
    public void setUp() throws Exception
    {
        service = EasyMock.createMock(MediaSearchServiceAsync.class);
        view = EasyMock.createMock(SynonymSearchResultView.class);
        handler = new ArtistSynonymQueryHandlerTestImpl(service);
    }

    /**
     * Helper method for creating a mock search result object.
     *
     * @return the mock object
     */
    private static SearchResult<ArtistInfo> createResultMock()
    {
        @SuppressWarnings("unchecked")
        SearchResult<ArtistInfo> result =
                EasyMock.createMock(SearchResult.class);
        return result;
    }

    /**
     * Helper method for creating a mock callback object.
     *
     * @return the mock object
     */
    private static AsyncCallback<SearchResult<ArtistInfo>> createCallbackMock()
    {
        @SuppressWarnings("unchecked")
        AsyncCallback<SearchResult<ArtistInfo>> result =
                EasyMock.createMock(AsyncCallback.class);
        return result;
    }

    /**
     * Creates a test search parameters object.
     *
     * @return the parameters object
     */
    private MediaSearchParameters createSearchParameters()
    {
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText(SEARCH_TEXT);
        params.setClientParameter(CLIENT_PARAM);
        return params;
    }

    /**
     * Tests the main method for querying synonyms.
     */
    @Test
    public void testQuerySynonyms()
    {
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                handler.installMockCallback(view);
        MediaSearchParameters params = createSearchParameters();
        service.searchArtists(params, null, callback);
        EasyMock.replay(callback, service, view);
        handler.querySynonyms(view, SEARCH_TEXT, CLIENT_PARAM);
        EasyMock.verify(callback, service, view);
    }

    /**
     * Tests whether synonym information from artist objects is correctly
     * extracted.
     */
    @Test
    public void testExtractSynonymData()
    {
        SearchResult<ArtistInfo> result = createResultMock();
        final int synCount = 8;
        List<ArtistInfo> infos = new ArrayList<ArtistInfo>(synCount);
        for (int i = 0; i < synCount; i++)
        {
            ArtistInfo info = new ArtistInfo();
            info.setArtistID(Long.valueOf(i));
            info.setName(SEARCH_TEXT + i);
            infos.add(info);
        }
        EasyMock.expect(result.getResults()).andReturn(infos);
        EasyMock.replay(service, view, result);
        Map<Object, String> synData = handler.extractSynonymData(result);
        assertEquals("Wrong number of synonyms", synCount, synData.size());
        for (int i = 0; i < synCount; i++)
        {
            Long key = Long.valueOf(i);
            assertEquals("Wrong value for " + key, SEARCH_TEXT + i,
                    synData.get(key));
        }
        EasyMock.verify(service, view, result);
    }

    /**
     * Tests the callback if more search results are available.
     */
    @Test
    public void testCallbackOnSuccessMoreResults()
    {
        SearchResult<ArtistInfo> result = createResultMock();
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        MediaSearchParameters params = createSearchParameters();
        EasyMock.expect(result.getSearchParameters()).andReturn(params)
                .anyTimes();
        EasyMock.expect(view.acceptResults(params)).andReturn(Boolean.TRUE);
        EasyMock.expect(result.getSearchIterator()).andReturn(sit).anyTimes();
        EasyMock.expect(sit.hasNext()).andReturn(Boolean.TRUE).anyTimes();
        Map<Object, String> synData = handler.installMockSynonymData(result);
        view.addResults(synData, true);
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                handler.createCallback(view);
        service.searchArtists(params, sit, callback);
        EasyMock.replay(result, sit, service, view);
        callback.onSuccess(result);
        EasyMock.verify(result, sit, service, view);
    }

    /**
     * Tests the callback if the end of the search has been reached.
     */
    @Test
    public void testCallbackOnSuccessSearchComplete()
    {
        SearchResult<ArtistInfo> result = createResultMock();
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        MediaSearchParameters params = createSearchParameters();
        EasyMock.expect(result.getSearchParameters()).andReturn(params);
        EasyMock.expect(view.acceptResults(params)).andReturn(Boolean.TRUE);
        EasyMock.expect(result.getSearchIterator()).andReturn(sit).anyTimes();
        EasyMock.expect(sit.hasNext()).andReturn(Boolean.FALSE).anyTimes();
        Map<Object, String> synData = handler.installMockSynonymData(result);
        view.addResults(synData, false);
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                handler.createCallback(view);
        EasyMock.replay(result, sit, service, view);
        callback.onSuccess(result);
        EasyMock.verify(result, sit, service, view);
    }

    /**
     * Tests the callback if the view does not accept the search results.
     */
    @Test
    public void testCallbackOnSuccessNotAccepted()
    {
        SearchResult<ArtistInfo> result = createResultMock();
        MediaSearchParameters params = createSearchParameters();
        EasyMock.expect(result.getSearchParameters()).andReturn(params);
        EasyMock.expect(view.acceptResults(params)).andReturn(Boolean.FALSE);
        EasyMock.replay(result, service, view);
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                handler.createCallback(view);
        callback.onSuccess(result);
        EasyMock.verify(result, service, view);
    }

    /**
     * Tests the error handling of the callback.
     */
    @Test
    public void testCallbackOnFailure()
    {
        Throwable ex = new Exception("TestException");
        view.onFailure(ex);
        EasyMock.replay(view, service);
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                handler.createCallback(view);
        callback.onFailure(ex);
        EasyMock.verify(view, service);
    }

    /**
     * A test implementation of the artist query handler with enhanced mocking
     * facilities.
     */
    private static class ArtistSynonymQueryHandlerTestImpl extends
            ArtistSynonymQueryHandler
    {
        /** A mock for a callback object. */
        private AsyncCallback<SearchResult<ArtistInfo>> mockCallback;

        /** A map with synonym data to be returned by extractSynonymData(). */
        private Map<Object, String> synData;

        /** The expected result view. */
        private SynonymSearchResultView expectedView;

        /** The expected search results. */
        private SearchResult<ArtistInfo> expectedResult;

        public ArtistSynonymQueryHandlerTestImpl(MediaSearchServiceAsync service)
        {
            super(service);
        }

        /**
         * Installs a mock callback.
         *
         * @param expView the expected results view
         * @return the mock callback
         */
        public AsyncCallback<SearchResult<ArtistInfo>> installMockCallback(
                SynonymSearchResultView expView)
        {
            mockCallback = createCallbackMock();
            expectedView = expView;
            return mockCallback;
        }

        /**
         * Installs mock synonym data to be returned by extractSynonymData().
         *
         * @param expResult the expected search result object
         * @return the mock data map
         */
        public Map<Object, String> installMockSynonymData(
                SearchResult<ArtistInfo> expResult)
        {
            synData =
                    Collections
                            .singletonMap((Object) CLIENT_PARAM, SEARCH_TEXT);
            expectedResult = expResult;
            return synData;
        }

        /**
         * Either returns the mock callback or calls the super method.
         */
        @Override
        protected AsyncCallback<SearchResult<ArtistInfo>> createCallback(
                SynonymSearchResultView view)
        {
            if (mockCallback != null)
            {
                assertSame("Wrong view", expectedView, view);
                return mockCallback;
            }
            return super.createCallback(view);
        }

        /**
         * Either returns the test synonym data or calls the super method.
         */
        @Override
        protected Map<Object, String> extractSynonymData(
                SearchResult<ArtistInfo> results)
        {
            if (synData != null)
            {
                assertSame("Wrong search result object", expectedResult,
                        results);
                return synData;
            }
            return super.extractSynonymData(results);
        }
    }
}
