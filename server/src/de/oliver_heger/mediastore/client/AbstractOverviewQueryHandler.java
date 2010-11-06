package de.oliver_heger.mediastore.client;

import java.io.Serializable;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchService;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * <p>
 * A base implementation of a handler class for search queries of an overview
 * table.
 * </p>
 * <p>
 * A concrete subclass of this base class is triggered whenever the user presses
 * the search button in one of the overview pages. This causes the corresponding
 * search query to be sent to the server. When the result is received (which can
 * happen in multiple chunks) the overview table is updated.
 * </p>
 * <p>
 * This base class already provides basic functionality for managing the query
 * service and for dealing with a {@link SearchResultView} implementation.
 * Concrete subclasses mainly have to implement the query execution.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of query results processed by this handler
 */
abstract class AbstractOverviewQueryHandler<T>
{
    /** Stores a reference to the associated view component. */
    private final SearchResultView view;

    /** Stores the search service. */
    private MediaSearchServiceAsync searchService;

    /**
     * Creates a new instance of {@code AbstractOverviewQueryHandler} and
     * initializes it with the associated {@link SearchResultView} component.
     *
     * @param v the view object to be served by this handler
     */
    protected AbstractOverviewQueryHandler(SearchResultView v)
    {
        view = v;
    }

    /**
     * Returns a reference to the associated {@link SearchResultView}.
     *
     * @return the view object
     */
    public SearchResultView getView()
    {
        return view;
    }

    /**
     * Returns the search service.
     *
     * @return the search service
     */
    public MediaSearchServiceAsync getSearchService()
    {
        if (searchService == null)
        {
            searchService = GWT.create(MediaSearchService.class);
        }
        return searchService;
    }

    /**
     * Processes a search query. This method should be called if the user
     * presses the search button in an overview table. This implementation
     * creates a callback object and passes it to the {@code callService()}
     * method. A concrete subclass can then call the correct service method.
     * Later on the results obtained from the server are transformed into a
     * {@link ResultData} object and passed to the view component. The passed in
     * search iterator determines the position where to continue the search. It
     * is <b>null</b> if a new search is started.
     *
     * @param searchParams the parameters for the search (obtained from the
     *        overview table)
     * @param searchIterator the search iterator
     */
    public void handleQuery(MediaSearchParameters searchParams,
            SearchIterator searchIterator)
    {
        callService(getSearchService(), searchParams, searchIterator,
                createQueryCallback(searchParams));
    }

    /**
     * Creates the callback object which will be used for a service call.
     *
     * @param params the search parameters object
     * @return the new callback object
     */
    protected AsyncCallback<SearchResult<T>> createQueryCallback(
            MediaSearchParameters params)
    {
        return new QueryCallback(params.getClientParameter());
    }

    /**
     * Invokes the search service. A concrete implementation must call the
     * correct service method here.
     *
     * @param service the service
     * @param searchParams the parameters for the search
     * @param searchIterator the search iterator
     * @param callback the callback object to be used
     */
    protected abstract void callService(MediaSearchServiceAsync service,
            MediaSearchParameters searchParams, SearchIterator searchIterator,
            AsyncCallback<SearchResult<T>> callback);

    /**
     * Creates a {@link ResultData} object for the result data obtained from the
     * server.
     *
     * @param result the result object returned by the service call
     * @return the corresponding {@link ResultData} object
     */
    protected abstract ResultData createResult(SearchResult<T> result);

    /**
     * Creates a callback object that can be used to determine whether more
     * search results are available. This method is called at the end of a
     * search operation if there are still records to search.
     *
     * @param parent the parent callback object
     * @param result the last result object returned from the server
     * @return a callback object to be used for further server interaction
     */
    AsyncCallback<SearchResult<T>> createMoreResultsCallback(
            QueryCallback parent, SearchResult<T> result)
    {
        return new MoreResultsCallback(parent, result);
    }

    /**
     * Checks if further search results are available. This method is called at
     * the end of a search operation that returned the maximum number of search
     * results. It checks whether there is at least one more match in the
     * records that have not been searched yet. This information has to be
     * passed to the view component so that it can update its paging controls
     * correspondingly.
     *
     * @param queryCallback the query callback which is currently active
     * @param result the last result object returned from the server
     */
    void checkForMoreResults(QueryCallback queryCallback, SearchResult<T> result)
    {
        MediaSearchParameters params = result.getSearchParameters();
        params.setMaxResults(1);
        callService(getSearchService(), params, result.getSearchIterator(),
                createMoreResultsCallback(queryCallback, result));
    }

    /**
     * An implementation of the asynchronous callback class required for calling
     * the search service. Derived classes use an instance of this class when
     * invoking the search service. This class already implements the
     * asynchronous methods.
     */
    class QueryCallback implements AsyncCallback<SearchResult<T>>
    {
        /** Stores the client parameter used for the current search. */
        private final Serializable clientParameter;

        /**
         * Creates a new instance of {@code QueryCallback} and initializes it
         * with the client parameter for the current search.
         *
         * @param param the client parameter
         */
        public QueryCallback(Serializable param)
        {
            clientParameter = param;
        }

        /**
         * The current search is complete. This method notifies the associated
         * view component.
         *
         * @param searchIterator the search iterator
         * @param clientParam the client parameter of the current search
         * @param moreResults a flag whether more results are available
         */
        public void searchComplete(SearchIterator searchIterator,
                Object clientParam, boolean moreResults)
        {
            getView().searchComplete(searchIterator, clientParam, moreResults);
        }

        /**
         * An exception has been thrown when the server was called. This
         * implementation passes the exception to the associated view component.
         *
         * @param caught the caught exception
         */
        @Override
        public void onFailure(Throwable caught)
        {
            getView().onFailure(caught, clientParameter);
        }

        /**
         * The result of the server call was received. This implementation
         * creates a corresponding {@link ResultData} object and updates the
         * associated view component. If there are more results and the maximum
         * limit of hits has not yet been reached, the search is continued.
         *
         * @param result the result
         */
        @Override
        public void onSuccess(SearchResult<T> result)
        {
            MediaSearchParameters params = result.getSearchParameters();
            getView().addSearchResults(createResult(result),
                    params.getClientParameter());

            SearchIterator sit = result.getSearchIterator();
            if (sit.hasNext())
            {
                // one chunk was processed; is the search limit reached?
                int resultCount = result.getResults().size();
                boolean unlimited = params.getMaxResults() <= 0;
                if (unlimited || resultCount < params.getMaxResults())
                {
                    params.setMaxResults(unlimited ? 0 : params.getMaxResults()
                            - resultCount);
                    callService(getSearchService(), params, sit, this);
                }

                else
                {
                    // search limit was reached; check if there are more results
                    checkForMoreResults(this, result);
                }
            }

            else
            {
                // all chunks have been processed
                Integer currentPage = sit.getCurrentPage();
                boolean moreResults =
                        (currentPage != null) ? currentPage.intValue() < sit
                                .getPageCount().intValue() - 1 : false;
                searchComplete(sit, params.getClientParameter(), moreResults);
            }
        }
    }

    /**
     * A specialized callback implementation which is used if the limit of a
     * search query is reached. In this case it has to be checked whether there
     * are more results, so that the view component can update its paging
     * controls. This class invokes the search service in a loop until either
     * another result is found or all records have been searched.
     */
    private class MoreResultsCallback implements AsyncCallback<SearchResult<T>>
    {
        /** A reference to the query callback which created this object. */
        private final QueryCallback queryCallback;

        /** The result data object for the last chunk of the current search. */
        private final SearchResult<T> lastResult;

        /**
         * Creates a new instance of {@code MoreResultsCallback} and initializes
         * it.
         *
         * @param qcb the query callback
         * @param res the last result object
         */
        public MoreResultsCallback(QueryCallback qcb, SearchResult<T> res)
        {
            queryCallback = qcb;
            lastResult = res;
        }

        /**
         * An error occurred on the server. This implementation delegates to the
         * query callback.
         *
         * @param caught the error
         */
        @Override
        public void onFailure(Throwable caught)
        {
            queryCallback.onFailure(caught);
        }

        /**
         * A chunk of data was successfully searched on the server. This method
         * checks whether a result was found. If yes, we know that there are
         * more results. Otherwise, the service has to be called again for the
         * next chunk. If all chunks have been processed, we know that there are
         * no more results.
         *
         * @param result the result object
         */
        @Override
        public void onSuccess(SearchResult<T> result)
        {
            if (!result.getResults().isEmpty())
            {
                searchComplete(true);
            }
            else
            {
                SearchIterator sit = result.getSearchIterator();
                if (sit.hasNext())
                {
                    callService(getSearchService(),
                            result.getSearchParameters(), sit, this);
                }
                else
                {
                    searchComplete(false);
                }
            }
        }

        /**
         * Terminates the search and sets the flag whether more results are
         * available.
         *
         * @param moreResults a flag whether more results are available
         */
        private void searchComplete(boolean moreResults)
        {
            queryCallback.searchComplete(lastResult.getSearchIterator(),
                    lastResult.getSearchParameters().getClientParameter(),
                    moreResults);
        }
    }
}
