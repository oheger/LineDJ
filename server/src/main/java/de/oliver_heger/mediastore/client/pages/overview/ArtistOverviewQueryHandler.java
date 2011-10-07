package de.oliver_heger.mediastore.client.pages.overview;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.model.ArtistInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * <p>
 * The specific {@code OverviewQueryHandler} for artists.
 * </p>
 * <p>
 * This class performs queries for artists.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class ArtistOverviewQueryHandler implements OverviewQueryHandler<ArtistInfo>
{
    /**
     * {@inheritDoc} This implementation calls the method of the media search
     * service which performs an artist search.
     */
    @Override
    public void executeQuery(MediaSearchServiceAsync service,
            MediaSearchParameters searchParams, SearchIterator searchIterator,
            AsyncCallback<SearchResult<ArtistInfo>> callback)
    {
        service.searchArtists(searchParams, searchIterator, callback);
    }
}
