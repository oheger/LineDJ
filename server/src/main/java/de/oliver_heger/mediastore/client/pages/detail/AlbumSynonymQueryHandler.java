package de.oliver_heger.mediastore.client.pages.detail;

import java.util.Map;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.model.AlbumInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * <p>
 * A specialized {@link SynonymQueryHandler} implementation for querying
 * synonyms of albums.
 * </p>
 * <p>
 * This class is used by the details page for albums for dealing with synonyms.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class AlbumSynonymQueryHandler extends AbstractSynonymQueryHandler<AlbumInfo>
{
    /**
     * Creates a new instance of {@code AlbumSynonymQueryHandler} and sets the
     * reference to the search service.
     *
     * @param service the search service reference
     */
    public AlbumSynonymQueryHandler(MediaSearchServiceAsync service)
    {
        super(service);
    }

    /**
     * {@inheritDoc} This implementation calls the service method to search for
     * albums.
     */
    @Override
    protected void callSearchService(MediaSearchParameters params,
            SearchIterator it, AsyncCallback<SearchResult<AlbumInfo>> callback)
    {
        getSearchService().searchAlbums(params, it, callback);
    }

    /**
     * {@inheritDoc} This implementation adds the album name to the map using
     * the album ID as key.
     */
    @Override
    protected void extractSynonymDataFromEntity(Map<Object, String> target,
            AlbumInfo entity)
    {
        target.put(entity.getAlbumID(), entity.getName());
    }
}
