package de.oliver_heger.mediastore.client.pages.detail;

import java.util.Map;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.model.SongInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * <p>
 * A specialized {@link SynonymQueryHandler} implementation for songs.
 * </p>
 * <p>
 * This implementation is used by the details page for songs to search for
 * potential synonyms.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class SongSynonymQueryHandler extends AbstractSynonymQueryHandler<SongInfo>
{
    /**
     * Creates a new instance of {@code SongSynonymQueryHandler} and initializes
     * it with the given search service.
     *
     * @param service the {@link MediaSearchServiceAsync}
     */
    public SongSynonymQueryHandler(MediaSearchServiceAsync service)
    {
        super(service);
    }

    /**
     * {@inheritDoc} This implementation calls the method for searching for
     * songs.
     */
    @Override
    protected void callSearchService(MediaSearchParameters params,
            SearchIterator it, AsyncCallback<SearchResult<SongInfo>> callback)
    {
        getSearchService().searchSongs(params, it, callback);
    }

    /**
     * {@inheritDoc} This implementation extracts the ID and the name from the
     * given song entity object.
     */
    @Override
    protected void extractSynonymDataFromEntity(Map<Object, String> target,
            SongInfo entity)
    {
        target.put(entity.getSongID(), entity.getName());
    }
}
