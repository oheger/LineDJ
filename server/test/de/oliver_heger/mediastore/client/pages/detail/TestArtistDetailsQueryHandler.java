package de.oliver_heger.mediastore.client.pages.detail;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import com.google.gwt.user.client.rpc.AsyncCallback;

import de.oliver_heger.mediastore.shared.BasicMediaServiceAsync;
import de.oliver_heger.mediastore.shared.model.ArtistDetailInfo;

/**
 * Test class for {@code ArtistDetailsQueryHandler}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestArtistDetailsQueryHandler
{
    /** Constant for the ID of an artist. */
    private static final long ARTIST_ID = 20101217183157L;

    /** The handler to be tested. */
    private ArtistDetailsQueryHandler handler;

    @Before
    public void setUp() throws Exception
    {
        handler = new ArtistDetailsQueryHandler();
    }

    /**
     * Tests whether details of an artist can be fetched.
     */
    @Test
    public void testFetchDetails()
    {
        BasicMediaServiceAsync service =
                EasyMock.createMock(BasicMediaServiceAsync.class);
        @SuppressWarnings("unchecked")
        AsyncCallback<ArtistDetailInfo> cb =
                EasyMock.createMock(AsyncCallback.class);
        service.fetchArtistDetails(ARTIST_ID, cb);
        EasyMock.replay(service, cb);
        handler.fetchDetails(service, String.valueOf(ARTIST_ID), cb);
        EasyMock.verify(service, cb);
    }
}
