package de.oliver_heger.mediastore.client.pages.detail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.HashMap;
import java.util.Map;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.model.SongInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * Test class for {@code SongSynonymQueryHandler}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSongSynonymQueryHandler
{
    /** The mock search service. */
    private MediaSearchServiceAsync searchService;

    /** The handler to be tested. */
    private SongSynonymQueryHandler handler;

    @Before
    public void setUp() throws Exception
    {
        searchService = EasyMock.createMock(MediaSearchServiceAsync.class);
        handler = new SongSynonymQueryHandler(searchService);
    }

    /**
     * Tests a newly initialized object.
     */
    @Test
    public void testInit()
    {
        assertSame("Wrong search service", searchService,
                handler.getSearchService());
    }

    /**
     * Tests whether the search service is called correctly.
     */
    @Test
    public void testCallSearchService()
    {
        @SuppressWarnings("unchecked")
        AsyncCallback<SearchResult<SongInfo>> callback =
                EasyMock.createMock(AsyncCallback.class);
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        MediaSearchParameters params = new MediaSearchParameters();
        params.setSearchText("TestSong");
        searchService.searchSongs(params, sit, callback);
        EasyMock.replay(searchService, sit, callback);
        handler.callSearchService(params, sit, callback);
        EasyMock.verify(searchService, sit, callback);
    }

    /**
     * Tests whether synonym data is correctly extracted from a song entity.
     */
    @Test
    public void testExtractSynonymDataFromEntity()
    {
        final String songID = "TestSongID";
        final String songName = "LaLaLa";
        SongInfo info = new SongInfo();
        info.setSongID(songID);
        info.setName(songName);
        Map<Object, String> map = new HashMap<Object, String>();
        handler.extractSynonymDataFromEntity(map, info);
        assertEquals("Wrong number of map entries", 1, map.size());
        assertEquals("Wrong mapping", songName, map.get(songID));
    }
}
