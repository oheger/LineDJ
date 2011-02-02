package de.oliver_heger.mediastore.client.pages.detail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.HashMap;
import java.util.Map;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.model.AlbumInfo;
import de.oliver_heger.mediastore.shared.search.MediaSearchParameters;
import de.oliver_heger.mediastore.shared.search.MediaSearchServiceAsync;
import de.oliver_heger.mediastore.shared.search.SearchIterator;
import de.oliver_heger.mediastore.shared.search.SearchResult;

/**
 * Test class for {@code AlbumSynonymQueryHandler}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAlbumSynonymQueryHandler
{
    /** Stores the mock search service. */
    private MediaSearchServiceAsync service;

    /** The handler to be tested. */
    private AlbumSynonymQueryHandler handler;

    @Before
    public void setUp() throws Exception
    {
        service = EasyMock.createMock(MediaSearchServiceAsync.class);
        handler = new AlbumSynonymQueryHandler(service);
    }

    /**
     * Tests whether the search service has been correctly initialized.
     */
    @Test
    public void testGetSearchService()
    {
        assertSame("Wrong search service", service, handler.getSearchService());
    }

    /**
     * Tests whether the search service is called correctly.
     */
    @Test
    public void testCallSearchService()
    {
        @SuppressWarnings("unchecked")
        AsyncCallback<SearchResult<AlbumInfo>> callback =
                EasyMock.createMock(AsyncCallback.class);
        SearchIterator sit = EasyMock.createMock(SearchIterator.class);
        MediaSearchParameters params = new MediaSearchParameters();
        service.searchAlbums(params, sit, callback);
        EasyMock.replay(service, callback, sit);
        handler.callSearchService(params, sit, callback);
        EasyMock.verify(service, callback, sit);
    }

    /**
     * Tests whether the album name is correctly extracted.
     */
    @Test
    public void testExtractSynonymDataFromEntity()
    {
        AlbumInfo info = new AlbumInfo();
        info.setAlbumID(20110202214426L);
        info.setName("Brothers in Arms");
        Map<Object, String> data = new HashMap<Object, String>();
        handler.extractSynonymDataFromEntity(data, info);
        assertEquals("Wrong map size", 1, data.size());
        assertEquals("Wrong entry", info.getName(), data.get(info.getAlbumID()));
    }
}
