package de.oliver_heger.mediastore.client.pages.overview;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * <p>
 * Definition of an interface used by the data provider for overview pages to
 * execute queries on the media search service.
 * </p>
 * <p>
 * A concrete implementation of this interface has to call the correct service
 * method to execute a search for the corresponding media type.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 * @param <T> the type of data objects this handler deals with
 */
public interface OverviewQueryHandler<T>
{
    void executeQuery(MediaSearchServiceAsync service,
            MediaSearchParameters searchParams, SearchIterator searchIterator,
            AsyncCallback<SearchResult<T>> callback);
}
