package de.oliver_heger.mediastore.client.pages.overview;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.model.SongInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * <p>
 * A specialized {@code OverviewQueryHandler} for songs.
 * </p>
 * <p>
 * This implementation calls the method of the search service which allows
 * querying for songs.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SongOverviewQueryHandler implements OverviewQueryHandler<SongInfo>
{
    /**
     * {@inheritDoc} Performs a search for songs.
     */
    @Override
    public void executeQuery(MediaSearchServiceAsync service,
            MediaSearchParameters searchParams, SearchIterator searchIterator,
            AsyncCallback<SearchResult<SongInfo>> callback)
    {
        service.searchSongs(searchParams, searchIterator, callback);
    }
}
