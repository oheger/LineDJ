package de.oliver_heger.mediastore.client.pages.overview;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.model.ArtistInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * Test class for {@code ArtistOverviewQueryHandler}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestArtistOverviewQueryHandler
{
    /** The handler to be tested. */
    private ArtistOverviewQueryHandler handler;

    @Before
    public void setUp() throws Exception
    {
        handler = new ArtistOverviewQueryHandler();
    }

    /**
     * Tests whether an artist query can be performed.
     */
    @Test
    public void testExecuteQuery()
    {
        MediaSearchServiceAsync service =
                EasyMock.createMock(MediaSearchServiceAsync.class);
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        @SuppressWarnings("unchecked")
        AsyncCallback<SearchResult<ArtistInfo>> callback =
                EasyMock.createMock(AsyncCallback.class);
        MediaSearchParameters params = new MediaSearchParameters();
        service.searchArtists(params, sit, callback);
        EasyMock.replay(service, sit, callback);
        handler.executeQuery(service, params, sit, callback);
        EasyMock.verify(service, sit, callback);
    }
}
