package de.oliver_heger.mediastore.client.pages.detail;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;
import de.oliver_heger.mediastore.shared.SynonymUpdateData;
import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;

/**
 * Test class for {@code ArtistDetailsEntityHandler}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestArtistDetailsEntityHandler
{
    /** Constant for the ID of an artist. */
    private static final long ARTIST_ID = 20101217183157L;

    /** The handler to be tested. */
    private ArtistDetailsEntityHandler handler;

    @Before
    public void setUp() throws Exception
    {
        handler = new ArtistDetailsEntityHandler();
    }

    /**
     * Helper method for creating a callback mock of the specified type.
     *
     * @param <T> the type of the mock
     * @return the callback mock
     */
    private static <T> AsyncCallback<T> createCallbackMock()
    {
        @SuppressWarnings("unchecked")
        AsyncCallback<T> result = EasyMock.createMock(AsyncCallback.class);
        return result;
    }

    /**
     * Tests whether details of an artist can be fetched.
     */
    @Test
    public void testFetchDetails()
    {
        BasicMediaServiceAsync service =
                EasyMock.createMock(BasicMediaServiceAsync.class);
        AsyncCallback<ArtistDetailInfo> cb = createCallbackMock();
        service.fetchArtistDetails(ARTIST_ID, cb);
        EasyMock.replay(service, cb);
        handler.fetchDetails(service, String.valueOf(ARTIST_ID), cb);
        EasyMock.verify(service, cb);
    }

    /**
     * Tests whether synonyms can be updated.
     */
    @Test
    public void testUpdateSynonyms()
    {
        BasicMediaServiceAsync service =
                EasyMock.createMock(BasicMediaServiceAsync.class);
        AsyncCallback<Void> cb = createCallbackMock();
        SynonymUpdateData upData = new SynonymUpdateData();
        service.updateArtistSynonyms(ARTIST_ID, upData, cb);
        EasyMock.replay(service, cb);
        handler.updateSynonyms(service, String.valueOf(ARTIST_ID), upData, cb);
        EasyMock.verify(service, cb);
    }
}
