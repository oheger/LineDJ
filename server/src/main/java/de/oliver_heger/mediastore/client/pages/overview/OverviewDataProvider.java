package de.oliver_heger.mediastore.client.pages.overview;

import java.util.ArrayList;
import java.util.List;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.view.client.AsyncDataProvider;
import com.google.gwt.view.client.HasData;
import com.google.gwt.view.client.Range;

import de.oliver_heger.mediastore.shared.ObjectUtils;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.OrderDef;
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
 * <p>
 * As became obvious, different strategies have to be implemented for simple
 * searches or for searches involving multiple server round-trips. In the latter
 * case, the class has to keep a cache with the results obtained so far so that
 * the cell widget can be served when the user scrolls to another position.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of data objects this data provider deals with
 */
public class OverviewDataProvider<T> extends AsyncDataProvider<T> implements
        SearchResultConsumer<T>
{
    /** Constant for an empty search string. */
    private static final String NO_SEARCH_TEXT = "";

    /** The search service. */
    private final MediaSearchServiceAsync searchService;

    /** The query handler. */
    private final OverviewQueryHandler<T> queryHandler;

    /** The callback factory. */
    private final OverviewCallbackFactory<T> callbackFactory;

    /** The order definition provider. */
    private final OrderDefinitionProvider orderProvider;

    /** The current search text. */
    private String searchText;

    /** The current search order. */
    private List<OrderDef> currentOrder;

    /** A list with the results of the last text search. */
    private List<T> searchResults;

    /** The current search parameter. */
    private long currentSearchParameter;

    /** The refresh flag. */
    private boolean refreshFlag;

    /**
     * Creates a new instance of {@code OverviewDataProvider} and initializes it
     * with all required dependencies.
     *
     * @param service the media search service
     * @param handler the query handler
     * @param factory the callback factory
     * @param odProvider the provider for order definitions
     */
    public OverviewDataProvider(MediaSearchServiceAsync service,
            OverviewQueryHandler<T> handler,
            OverviewCallbackFactory<T> factory,
            OrderDefinitionProvider odProvider)
    {
        searchService = service;
        queryHandler = handler;
        callbackFactory = factory;
        orderProvider = odProvider;
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
     * Returns the {@code OrderDefinitionProvider} used by this object.
     *
     * @return the {@code OrderDefinitionProvider}
     */
    public OrderDefinitionProvider getOrderDefinitionProvider()
    {
        return orderProvider;
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
        refresh();
    }

    /**
     * Enforces a refresh. After this method was called, data will be loaded
     * anew even if the search text did no change.
     */
    public void refresh()
    {
        refreshFlag = true;
    }

    /**
     * Notifies this object that new search results have been received. If the
     * parameter matches the current search sequence number, the result list is
     * added to an internal cache. The widget is also updated correspondingly.
     *
     * @param results the list with the received result objects
     * @param widget the widget to be updated
     * @param param the search client parameter
     */
    @Override
    public void searchResultsReceived(List<T> results, HasData<T> widget,
            Object param)
    {
        if (Long.valueOf(currentSearchParameter).equals(param))
        {
            widget.setRowData(searchResults.size(), results);
            searchResults.addAll(results);
            widget.setRowCount(searchResults.size());
        }
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
     * Tests whether a new text search has to be performed. Normally, a search
     * is only started if some search parameters have changed. It is also
     * possible to enforce a new search by calling the {@code refresh()} method.
     *
     * @return a flag whether a new search has to be performed
     */
    boolean refreshRequired()
    {
        if (refreshFlag)
        {
            return true;
        }

        return !ObjectUtils.equals(currentOrder, getOrderDefinitionProvider()
                .getOrderDefinitions());
    }

    /**
     * Performs a simple search.
     *
     * @param display the widget to be updated
     */
    private void simpleSearch(HasData<T> display)
    {
        Range range = display.getVisibleRange();
        MediaSearchParameters params = createAndInitSearchParameters();
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
        if (refreshRequired())
        {
            initiateNewSearch(display);
        }
        else
        {
            updateWidgetFromAvailableData(display);
        }
    }

    /**
     * Starts a new parameters search.
     *
     * @param display the widget to be updated
     */
    private void initiateNewSearch(HasData<T> display)
    {
        display.setRowCount(0);
        searchResults = new ArrayList<T>();
        MediaSearchParameters params = createAndInitSearchParameters();
        currentOrder = params.getOrderDefinition();
        params.setSearchText(getSearchText());
        params.setClientParameter(Long.valueOf(++currentSearchParameter));
        AsyncCallback<SearchResult<T>> callback =
                getCallbackFactory().createParameterSearchCallback(
                        searchService, queryHandler, display, this);
        invokeQueryHandler(params, callback);
        refreshFlag = false;
    }

    /**
     * Updates the widget from data that has already been retrieved from the
     * server.
     *
     * @param display the widget to be updated
     */
    private void updateWidgetFromAvailableData(HasData<T> display)
    {
        Range range = display.getVisibleRange();
        assert searchResults != null : "No search results!";
        display.setRowData(
                range.getStart(),
                searchResults.subList(range.getStart(), Math.min(
                        range.getStart() + range.getLength(),
                        searchResults.size())));
    }

    /**
     * Creates the parameters object for a search operation. This method creates
     * a parameters object and performs some initializations common to all query
     * kinds.
     *
     * @return the {@code MediaSearchParameters} object
     */
    private MediaSearchParameters createAndInitSearchParameters()
    {
        MediaSearchParameters params = new MediaSearchParameters();
        params.setOrderDefinition(getOrderDefinitionProvider()
                .getOrderDefinitions());
        return params;
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
