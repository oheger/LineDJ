package de.oliver_heger.mediastore.client.pages.overview;

import java.io.Serializable;
import java.util.List;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.view.client.HasData;

import de.oliver_heger.mediastore.client.ErrorIndicator;
import de.oliver_heger.mediastore.shared.model.ArtistInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * Test class for {@code OverviewCallbackFactory}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestOverviewCallbackFactoryImpl
{
    /** Constant for the search parameter. */
    private static final Serializable SEARCH_PARAM = 20111114223208L;

    /** A mock for the error indicator. */
    private ErrorIndicator errorIndicator;

    /** The factory to be tested. */
    private OverviewCallbackFactoryImpl<ArtistInfo> factory;

    @Before
    public void setUp() throws Exception
    {
        errorIndicator = EasyMock.createMock(ErrorIndicator.class);
        factory = new OverviewCallbackFactoryImpl<ArtistInfo>(errorIndicator);
    }

    /**
     * Creates a mock for a cell widget.
     *
     * @return the widget mock
     */
    private static HasData<ArtistInfo> createCallback()
    {
        @SuppressWarnings("unchecked")
        HasData<ArtistInfo> widget = EasyMock.createMock(HasData.class);
        return widget;
    }

    /**
     * Creates a mock for a search result.
     *
     * @return the search result mock
     */
    private static SearchResult<ArtistInfo> createSearchResult()
    {
        @SuppressWarnings("unchecked")
        SearchResult<ArtistInfo> result =
                EasyMock.createMock(SearchResult.class);
        return result;
    }

    /**
     * Creates a mock for a list with search results.
     *
     * @return the mock for the list
     */
    private static List<ArtistInfo> createResultList()
    {
        @SuppressWarnings("unchecked")
        List<ArtistInfo> list = EasyMock.createMock(List.class);
        return list;
    }

    /**
     * Creates a mock for a query handler.
     *
     * @return the query handler mock
     */
    private static OverviewQueryHandler<ArtistInfo> createQueryHandler()
    {
        @SuppressWarnings("unchecked")
        OverviewQueryHandler<ArtistInfo> handler =
                EasyMock.createMock(OverviewQueryHandler.class);
        return handler;
    }

    /**
     * Creates a mock for a search result consumer.
     *
     * @return the consumer mock
     */
    private static SearchResultConsumer<ArtistInfo> createConsumer()
    {
        @SuppressWarnings("unchecked")
        SearchResultConsumer<ArtistInfo> consumer =
                EasyMock.createMock(SearchResultConsumer.class);
        return consumer;
    }

    /**
     * Tests the simple callback if an error occurs.
     */
    @Test
    public void testSimpleCallbackError()
    {
        HasData<ArtistInfo> widget = createCallback();
        Throwable err = new RuntimeException();
        errorIndicator.displayError(err);
        EasyMock.replay(errorIndicator, widget);
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                factory.createSimpleSearchCallback(widget);
        callback.onFailure(err);
        EasyMock.verify(errorIndicator, widget);
    }

    /**
     * Tests the parameters callback if an error occurs.
     */
    @Test
    public void testParamsCallbackError()
    {
        HasData<ArtistInfo> widget = createCallback();
        MediaSearchServiceAsync searchService =
                EasyMock.createMock(MediaSearchServiceAsync.class);
        SearchResultConsumer<ArtistInfo> consumer = createConsumer();
        OverviewQueryHandler<ArtistInfo> queryHandler = createQueryHandler();
        Throwable err = new RuntimeException();
        errorIndicator.displayError(err);
        EasyMock.replay(errorIndicator, widget, searchService, queryHandler,
                consumer);
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                factory.createParameterSearchCallback(searchService,
                        queryHandler, widget, consumer);
        callback.onFailure(err);
        EasyMock.verify(errorIndicator, widget, searchService, queryHandler,
                consumer);
    }

    /**
     * Tests a successful use case for the simple callback.
     */
    @Test
    public void testSimpleCallbackSuccess()
    {
        HasData<ArtistInfo> widget = createCallback();
        SearchResult<ArtistInfo> result = createSearchResult();
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        List<ArtistInfo> resList = createResultList();
        final int firstResult = 111;
        final long recCount = 20111002L;
        MediaSearchParameters params = new MediaSearchParameters();
        params.setFirstResult(firstResult);
        EasyMock.expect(result.getSearchIterator()).andReturn(sit).anyTimes();
        EasyMock.expect(result.getSearchParameters()).andReturn(params)
                .anyTimes();
        EasyMock.expect(result.getResults()).andReturn(resList);
        EasyMock.expect(sit.getRecordCount()).andReturn(recCount);
        widget.setRowCount((int) recCount);
        widget.setRowData(firstResult, resList);
        errorIndicator.clearError();
        EasyMock.replay(errorIndicator, widget, result, sit, resList);
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                factory.createSimpleSearchCallback(widget);
        callback.onSuccess(result);
        EasyMock.verify(errorIndicator, widget, result, sit, resList);
    }

    /**
     * Tests the parameter callback if no data has been received.
     */
    @Test
    public void testParameterCallbackNoDataInLastChunk()
    {
        SearchResultConsumer<ArtistInfo> consumer = createConsumer();
        HasData<ArtistInfo> widget = createCallback();
        SearchResult<ArtistInfo> result = createSearchResult();
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        List<ArtistInfo> resList = createResultList();
        MediaSearchServiceAsync searchService =
                EasyMock.createMock(MediaSearchServiceAsync.class);
        OverviewQueryHandler<ArtistInfo> queryHandler = createQueryHandler();
        EasyMock.expect(result.getResults()).andReturn(resList);
        EasyMock.expect(resList.isEmpty()).andReturn(Boolean.TRUE);
        EasyMock.expect(result.getSearchIterator()).andReturn(sit);
        EasyMock.expect(sit.hasNext()).andReturn(Boolean.FALSE);
        EasyMock.replay(widget, result, sit, resList, searchService,
                queryHandler, consumer);
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                factory.createParameterSearchCallback(searchService,
                        queryHandler, widget, consumer);
        callback.onSuccess(result);
        EasyMock.verify(widget, result, sit, resList, searchService,
                queryHandler, consumer);
    }

    /**
     * Tests the parameter callback if data was retrieved and there are more
     * chunks.
     */
    @Test
    public void testParameterCallbackGotDataAndMoreChunks()
    {
        SearchResultConsumer<ArtistInfo> consumer = createConsumer();
        HasData<ArtistInfo> widget = createCallback();
        SearchResult<ArtistInfo> result = createSearchResult();
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        List<ArtistInfo> resList = createResultList();
        MediaSearchServiceAsync searchService =
                EasyMock.createMock(MediaSearchServiceAsync.class);
        OverviewQueryHandler<ArtistInfo> queryHandler = createQueryHandler();
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                factory.createParameterSearchCallback(searchService,
                        queryHandler, widget, consumer);

        // First invocation
        EasyMock.expect(result.getResults()).andReturn(resList);
        EasyMock.expect(resList.isEmpty()).andReturn(Boolean.FALSE);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setClientParameter(SEARCH_PARAM);
        EasyMock.expect(result.getSearchParameters()).andReturn(params)
                .anyTimes();
        consumer.searchResultsReceived(resList, widget, SEARCH_PARAM);
        errorIndicator.clearError();
        EasyMock.expect(result.getSearchIterator()).andReturn(sit);
        EasyMock.expect(sit.hasNext()).andReturn(Boolean.TRUE);
        queryHandler.executeQuery(searchService, params, sit, callback);

        // Second invocation
        EasyMock.expect(result.getResults()).andReturn(resList);
        EasyMock.expect(resList.isEmpty()).andReturn(Boolean.FALSE);
        consumer.searchResultsReceived(resList, widget, SEARCH_PARAM);
        errorIndicator.clearError();
        EasyMock.expect(result.getSearchIterator()).andReturn(sit);
        EasyMock.expect(sit.hasNext()).andReturn(Boolean.FALSE);

        EasyMock.replay(widget, result, sit, resList, searchService,
                queryHandler, consumer);
        callback.onSuccess(result);
        callback.onSuccess(result);
        EasyMock.verify(widget, result, sit, resList, searchService,
                queryHandler, consumer);
    }
}
