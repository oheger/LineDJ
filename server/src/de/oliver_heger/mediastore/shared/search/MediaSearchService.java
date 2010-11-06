package de.oliver_heger.mediastore.shared.search;

import com.google.gwt.user.client.rpc.RemoteService;
import com.google.gwt.user.client.rpc.RemoteServiceRelativePath;

import de.oliver_heger.mediastore.shared.model.Artist;

/**
 * <p>
 * Service interface of the media search service.
 * </p>
 * <p>
 * This service provides methods for searching several types of elements
 * supported by the remote media store application.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
@RemoteServiceRelativePath("mediasearch")
public interface MediaSearchService extends RemoteService
{
    /**
     * Performs a search for artists. This method either searches for all
     * artists or for artists matching certain criteria. In the latter case the
     * search is performed in chunks because it may be expensive to scan the
     * whole table at once. When a chunk of data has been searched, the results
     * retrieved so far are returned. The client can then decide whether the
     * next chunk is to be searched. If no search criteria are specified, the
     * search results can be returned immediately.
     *
     * @param params search parameters
     * @param iterator the search iterator defining the current position in the
     *        search; when starting a new search <b>null</b> has to be passed in
     * @return partial search results
     */
    SearchResult<Artist> searchArtists(MediaSearchParameters params,
            SearchIterator iterator);

    /**
     * Creates some test data for the currently logged-in user. This method
     * exists for testing purposes only. It will later be removed.
     */
    void createTestData();
}
