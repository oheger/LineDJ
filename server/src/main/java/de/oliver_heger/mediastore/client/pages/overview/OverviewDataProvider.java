package de.oliver_heger.mediastore.client.pages.overview;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.view.client.AsyncDataProvider;
import com.google.gwt.view.client.HasData;
import com.google.gwt.view.client.Range;

import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * <p>
 * A data provider for overview pages.
 * </p>
 * <p>
 * This class implements a data source for cell widgets on an overview page. The
 * widget calls back to the provider whenever it needs data. This class then
 * calls the media search service and pushes the results into the widget.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of data objects this data provider deals with
 */
public class OverviewDataProvider<T> extends AsyncDataProvider<T>
{
    /** Constant for an empty search string. */
    private static final String NO_SEARCH_TEXT = "";

    /** The search service. */
    private final MediaSearchServiceAsync searchService;

    /** The query handler. */
    private final OverviewQueryHandler<T> queryHandler;

    /** The callback factory. */
    private final OverviewCallbackFactory<T> callbackFactory;

    /** The current search text. */
    private String searchText;

    /**
     * Creates a new instance of {@code OverviewDataProvider} and initializes it
     * with the media search service, the query handler, and the callback
     * factory.
     *
     * @param service the media search service
     * @param handler the query handler
     * @param factory the callback factory
     */
    public OverviewDataProvider(MediaSearchServiceAsync service,
            OverviewQueryHandler<T> handler, OverviewCallbackFactory<T> factory)
    {
        searchService = service;
        queryHandler = handler;
        callbackFactory = factory;
    }

    /**
     * Returns the {@code OverviewCallbackFactory} used by this data provider.
     *
     * @return the callback factory
     */
    public OverviewCallbackFactory<T> getCallbackFactory()
    {
        return callbackFactory;
    }

    /**
     * Returns the current search text. The search text is trimmed before it is
     * returned. Result is never <b>null</b>; if no search text has been set, an
     * empty string is returned.
     *
     * @return the current search text
     */
    public String getSearchText()
    {
        return (searchText == null) ? NO_SEARCH_TEXT : searchText.trim();
    }

    /**
     * Sets the current search text. This method is called by an overview page
     * when the user enters a search text. Then the associated cell widget is
     * refreshed. This causes the data provider to be invoked.
     *
     * @param searchText the search text
     */
    public void setSearchText(String searchText)
    {
        this.searchText = searchText;
    }

    /**
     * Notifies this data provider that new data has to be displayed. This
     * implementation behaves differently if a search text has been set or not.
     * If there is no search text, a simple search can be performed. Otherwise,
     * a complex parameter search is required. The main difference lies in the
     * callback object passed to the search service.
     *
     * @param display the widget object to be updated
     */
    @Override
    protected void onRangeChanged(HasData<T> display)
    {
        if (!hasSearchText())
        {
            simpleSearch(display);
        }
        else
        {
            parameterSearch(display);
        }
    }

    /**
     * Performs a simple search.
     *
     * @param display the widget to be updated
     */
    private void simpleSearch(HasData<T> display)
    {
        Range range = display.getVisibleRange();
        MediaSearchParameters params = new MediaSearchParameters();
        params.setFirstResult(range.getStart());
        params.setMaxResults(range.getLength());
        AsyncCallback<SearchResult<T>> callback =
                getCallbackFactory().createSimpleSearchCallback(display);
        invokeQueryHandler(params, callback);
    }

    /**
     * Performs a parameters search.
     *
     * @param display the widget to be updated
     */
    private void parameterSearch(HasData<T> display)
    {
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText(getSearchText());
        AsyncCallback<SearchResult<T>> callback =
                getCallbackFactory().createParameterSearchCallback(
                        searchService, queryHandler, display);
        invokeQueryHandler(params, callback);
    }

    /**
     * Helper method for calling the query handler.
     *
     * @param params the search parameters object
     * @param callback the callback
     */
    private void invokeQueryHandler(MediaSearchParameters params,
            AsyncCallback<SearchResult<T>> callback)
    {
        queryHandler.executeQuery(searchService, params, null, callback);
    }

    /**
     * Checks whether a search text has been entered.
     *
     * @return a flag whether a search text is available
     */
    private boolean hasSearchText()
    {
        return getSearchText().length() > 0;
    }
}
