package de.oliver_heger.mediastore.shared.search;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.model.ArtistInfo;

/**
 * The asynchronous counterpart of {@link MediaSearchService}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface MediaSearchServiceAsync
{
    void searchArtists(MediaSearchParameters params, SearchIterator iterator,
            AsyncCallback<SearchResult<ArtistInfo>> callback);

    void createTestData(AsyncCallback<Void> callback);
}
