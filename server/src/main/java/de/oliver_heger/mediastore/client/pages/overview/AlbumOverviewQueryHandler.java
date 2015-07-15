package de.oliver_heger.mediastore.client.pages.overview;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.model.AlbumInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * <p>
 * The specialized {@code OverviewQueryHandler} for albums.
 * </p>
 * <p>
 * This class allows querying for album entities.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class AlbumOverviewQueryHandler implements
        OverviewQueryHandler<AlbumInfo>
{
    /**
     * {@inheritDoc} This implementation invokes the method of the
     * {@code MediaSearchServiceAsync} which searches for albums.
     */
    @Override
    public void executeQuery(MediaSearchServiceAsync service,
            MediaSearchParameters searchParams, SearchIterator searchIterator,
            AsyncCallback<SearchResult<AlbumInfo>> callback)
    {
        service.searchAlbums(searchParams, searchIterator, callback);
    }
}
