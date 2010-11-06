package de.oliver_heger.mediastore.shared.search;

import java.util.List;

/**
 * <p>
 * Definition of an interface describing search results returned from the
 * server.
 * </p>
 * <p>
 * Using the methods defined by this interface the client can access search
 * results that have already become available during a search in progress. The
 * interface provides sufficient information for the client to decide whether
 * search is complete or more results could be pending. By calling the server in
 * a loop and collecting the results, eventually the full result set can be
 * retrieved.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of the result objects stored in this object
 */
public interface SearchResult<T>
{
    /**
     * Returns a list with the results retrieved by the last server call. These
     * results can be directly displayed to the user while in the background
     * search goes on.
     *
     * @return a list with result objects
     */
    List<T> getResults();

    /**
     * Returns the current {@link SearchIterator} object. This object can be
     * used to determine the current state of the search: whether it is complete
     * or whether still results are pending.
     *
     * @return the current {@link SearchIterator}
     */
    SearchIterator getSearchIterator();

    /**
     * Returns the search parameters object that was used for the current
     * search. This may be useful for the client so that it can distinguish
     * between different search requests.
     *
     * @return the search parameters for the current search
     */
    MediaSearchParameters getSearchParameters();
}
