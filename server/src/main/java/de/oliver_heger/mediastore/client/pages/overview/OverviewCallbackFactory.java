package de.oliver_heger.mediastore.client.pages.overview;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.view.client.HasData;

import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * <p>
 * Definition of a factory interface for the creation of callback objects.
 * </p>
 * <p>
 * This interface is used for server calls which require an
 * {@code AsyncCallback} object.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of data objects this factory has to deal with
 */
public interface OverviewCallbackFactory<T>
{
    /**
     * Creates a callback object for a simple search operation. A "simple"
     * search can be performed in a single step, i.e. when the server call
     * returns and the callback is invoked all data can be written into the
     * widget.
     *
     * @param queryHandler the {@code OverviewQueryHandler}
     * @param widget the cell widget to be populated with data
     * @return the callback object
     */
    AsyncCallback<SearchResult<T>> createSimpleSearchCallback(
            OverviewQueryHandler<T> queryHandler, HasData<T> widget);

    /**
     * Creates a callback object for a search operation with parameters. Such a
     * search can require multiple iterations and further server calls until all
     * results have been retrieved.
     *
     * @param searchService the media search service
     * @param queryHandler the {@code OverviewQueryHandler}
     * @param widget the cell widget to be populated with data
     * @return the callback object
     */
    AsyncCallback<SearchResult<T>> createParameterSearchCallback(
            MediaSearchServiceAsync searchService,
            OverviewQueryHandler<T> queryHandler, HasData<T> widget);
}
