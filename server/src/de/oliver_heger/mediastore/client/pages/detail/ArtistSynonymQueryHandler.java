package de.oliver_heger.mediastore.client.pages.detail;

import java.util.Map;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.model.ArtistInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * <p>
 * A specialized {@link SynonymQueryHandler} implementation for artists.
 * </p>
 * <p>
 * This implementation is used to search for potential synonyms for artists.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class ArtistSynonymQueryHandler extends AbstractSynonymQueryHandler<ArtistInfo>
{
    /**
     * Creates a new instance of {@code ArtistSynonymQueryHandler} and
     * initializes it with the search service.
     *
     * @param service the search service
     */
    public ArtistSynonymQueryHandler(MediaSearchServiceAsync service)
    {
        super(service);
    }

    /**
     * {@inheritDoc} This implementation calls the method for searching for
     * artists.
     */
    @Override
    protected void callSearchService(MediaSearchParameters params,
            SearchIterator it, AsyncCallback<SearchResult<ArtistInfo>> callback)
    {
        getSearchService().searchArtists(params, it, callback);
    }

    /**
     * {@inheritDoc} This implementation extracts the ID and the name from the
     * specified artist object.
     */
    @Override
    protected void extractSynonymDataFromEntity(Map<Object, String> target,
            ArtistInfo entity)
    {
        target.put(entity.getArtistID(), entity.getName());
    }
}
