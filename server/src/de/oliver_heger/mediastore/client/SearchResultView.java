package de.oliver_heger.mediastore.client;

import de.oliver_heger.mediastore.shared.search.SearchIterator;

/**
 * <p>
 * Definition of an interface for objects that can display results of a search
 * operation.
 * </p>
 * <p>
 * This interface is used by concrete handler implementations for queries for
 * overview pages. Through the methods provided here search results can be added
 * to the view (so that they already are displayed while the search operation is
 * still in progress). The view can also be notified when the search is complete
 * indicating whether there are further result pages. If an error occurs on the
 * server, it can also be passed to the view.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface SearchResultView
{
    /**
     * Adds new search results to the view. This method can be called multiple
     * times during a search operation as results become available. An
     * implementation should display the new results so that the user gets
     * feedback while the search operation is still running.
     *
     * @param data a data object with the results to be added
     * @param clientParam the parameter that was passed at the beginning of the
     *        search; it allows distinguishing between multiple search
     *        operations
     */
    void addSearchResults(ResultData data, Object clientParam);

    /**
     * Notifies this view object that a search operation has been completed.
     * This means that either the data available has been fully searched or that
     * the limit of search results has been reached which was specified at the
     * beginning of the search. The {@code moreResults} parameter can be used to
     * distinguish between these cases. An implementation should indicate to the
     * user that the current search is terminated. It can also setup paging
     * functionality if there are more results available.
     *
     * @param searchIterator the search iterator defining the position where the
     *        current search ended
     * @param clientParam the parameter that was passed at the beginning of the
     *        search; it allows distinguishing between multiple search
     *        operations
     * @param moreResults a flag whether more results are available
     */
    void searchComplete(SearchIterator searchIterator, Object clientParam,
            boolean moreResults);

    /**
     * Notifies this view object about an error that occurred during a search
     * operation. An implementation can for instance display an error message to
     * the user.
     *
     * @param err the exception returned from the server
     * @param clientParam the parameter that was passed at the beginning of the
     *        search; it allows distinguishing between multiple search
     *        operations
     */
    void onFailure(Throwable err, Object clientParam);
}
