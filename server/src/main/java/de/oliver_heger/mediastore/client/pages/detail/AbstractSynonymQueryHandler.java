package de.oliver_heger.mediastore.client.pages.detail;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * <p>
 * An abstract base class for concrete {@link SynonymQueryHandler}
 * implementations.
 * </p>
 * <p>
 * This base class implements the major part of the functionality for querying
 * entities as synonyms. It provides an implementation of an asynchronous
 * callback which manages a {@link SynonymSearchResultView}. Concrete subclasses
 * have to provide implementations for the actual server call and for extracting
 * the IDs of the entities retrieved.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of entities this handler deals with
 */
abstract class AbstractSynonymQueryHandler<T> implements SynonymQueryHandler
{
    /** The search service to be called. */
    private final MediaSearchServiceAsync searchService;

    /**
     * Creates a new instance of {@code AbstractSynonymQueryHandler} and
     * initializes it with the references to the search service.
     *
     * @param service the search service
     */
    protected AbstractSynonymQueryHandler(MediaSearchServiceAsync service)
    {
        searchService = service;
    }

    /**
     * Returns the {@link MediaSearchServiceAsync} to be called by this query
     * handler.
     *
     * @return the search service
     */
    public MediaSearchServiceAsync getSearchService()
    {
        return searchService;
    }

    /**
     * Executes a synonym query. This implementation calls the search service
     * with a specialized callback object which populates the search result view
     * when new results become available.
     *
     * @param view the view to be filled
     * @param searchText the search text
     * @param clientParam the client parameter for the search
     */
    @Override
    public void querySynonyms(SynonymSearchResultView view, String searchText,
            Serializable clientParam)
    {
        callSearchService(createSearchParameters(searchText, clientParam),
                null, createCallback(view));
    }

    /**
     * Creates a callback object for calling the search service.
     *
     * @param view the view which has to be filled with search results
     * @return the new callback object
     */
    protected AsyncCallback<SearchResult<T>> createCallback(
            SynonymSearchResultView view)
    {
        return new SearchCallback(view);
    }

    /**
     * Extracts the data required by the {@link SynonymSearchResultView} from
     * the given result object. This method iterates over all entities retrieved
     * by the search operation. On each it calls
     * {@link #extractSynonymDataFromEntity(Map, Object)} to obtain its ID and
     * name.
     *
     * @param results the object with search results
     * @return a map with the extracted data
     */
    protected Map<Object, String> extractSynonymData(SearchResult<T> results)
    {
        Map<Object, String> synData = new HashMap<Object, String>();

        for (T entity : results.getResults())
        {
            extractSynonymDataFromEntity(synData, entity);
        }

        return synData;
    }

    /**
     * Adds newly received search results to the view. This method is called
     * when new results from the server arrive.
     *
     * @param view the view to be filled
     * @param results the results object
     * @return a flag whether the results are accepted from the view
     */
    protected boolean fillView(SynonymSearchResultView view,
            SearchResult<T> results)
    {
        if (view.acceptResults(results.getSearchParameters()))
        {
            Map<Object, String> synData = extractSynonymData(results);
            view.addResults(synData, results.getSearchIterator().hasNext());
            return true;
        }

        return false;
    }

    /**
     * Invokes the search service. This method is called whenever a server call
     * is required.
     *
     * @param params the object with the search parameters
     * @param it the search iterator
     * @param callback the callback object
     */
    protected abstract void callSearchService(MediaSearchParameters params,
            SearchIterator it, AsyncCallback<SearchResult<T>> callback);

    /**
     * Extracts the data required by the {@link SynonymSearchResultView} from
     * the given entity object. A concrete implementation has to obtain the ID
     * and name from the given entity and add them to the specified target map.
     *
     * @param target the map in which to store the transformed results
     * @param entity the entity object
     */
    protected abstract void extractSynonymDataFromEntity(
            Map<Object, String> target, T entity);

    /**
     * Creates the search parameters object for the specified parameters.
     *
     * @param searchText the search text
     * @param clientParam the client parameter
     * @return the corresponding parameters object
     */
    private MediaSearchParameters createSearchParameters(String searchText,
            Serializable clientParam)
    {
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText(searchText);
        params.setClientParameter(clientParam);
        return params;
    }

    /**
     * A specialized callback implementation for filling the result view with
     * the search results retrieved from the server.
     */
    private class SearchCallback implements AsyncCallback<SearchResult<T>>
    {
        /** Stores a reference to the view for the search results. */
        private final SynonymSearchResultView resultView;

        /**
         * Creates a new instance of {@code SearchCallback} and sets the view to
         * be filled.
         *
         * @param view the view to be filled
         */
        public SearchCallback(SynonymSearchResultView view)
        {
            resultView = view;
        }

        /**
         * The server reports an error. This implementation delegates to the
         * result view.
         *
         * @param caught the exception thrown by the server
         */
        @Override
        public void onFailure(Throwable caught)
        {
            resultView.onFailure(caught);
        }

        /**
         * Results from the server were retrieved. This method passes the data
         * to the results view. If it is accepted and if there are more search
         * results, the server is called again.
         *
         * @param result the object with the result data
         */
        @Override
        public void onSuccess(SearchResult<T> result)
        {
            if (fillView(resultView, result))
            {
                if (result.getSearchIterator().hasNext())
                {
                    callSearchService(result.getSearchParameters(),
                            result.getSearchIterator(), this);
                }
            }
        }
    }
}
