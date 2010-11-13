package de.oliver_heger.mediastore.server.resources;

import static org.junit.Assert.assertSame;

import javax.ws.rs.core.Response;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.server.NotLoggedInException;
import de.oliver_heger.mediastore.server.sync.MediaSyncService;
import de.oliver_heger.mediastore.server.sync.SyncResult;
import de.oliver_heger.mediastore.service.ArtistData;
import de.oliver_heger.mediastore.service.ObjectFactory;

/**
 * Test class for {@code ArtistResource}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestArtistResource
{
    /** The resource to be tested. */
    ArtistResourceTestImpl resource;

    @Before
    public void setUp() throws Exception
    {
        resource = new ArtistResourceTestImpl();
    }

    /**
     * Tests the main putArtist() method.
     */
    @Test
    public void testPutArtist()
    {
        Response resp = Response.ok().build();
        ArtistData data = new ObjectFactory().createArtistData();
        resource.expectDoSync(data, resp);
        assertSame("Wrong response", resp, resource.putArtist(data));
    }

    /**
     * Tests whether the sync service is correctly invoked.
     */
    @Test
    public void testInvokeSyncService() throws NotLoggedInException
    {
        MediaSyncService service = EasyMock.createMock(MediaSyncService.class);
        @SuppressWarnings("unchecked")
        SyncResult<Long> result = EasyMock.createMock(SyncResult.class);
        ArtistData data = new ObjectFactory().createArtistData();
        EasyMock.expect(service.syncArtist(data)).andReturn(result);
        EasyMock.replay(service);
        assertSame("Wrong result", result,
                resource.invokeSycService(service, data));
        EasyMock.verify(service);
    }

    /**
     * A test implementation of ArtistResource.
     */
    private static class ArtistResourceTestImpl extends ArtistResource
    {
        /** The expected artist data object. */
        private ArtistData expArtist;

        /** The response to be returned. */
        private Response response;

        /**
         * Prepares this object to expect a call to doSync().
         *
         * @param data the expected data object
         * @param resp the response to be returned
         */
        public void expectDoSync(ArtistData data, Response resp)
        {
            expArtist = data;
            response = resp;
        }

        /**
         * If this call is expected, parameters are checked, and the mock
         * response is returned.
         */
        @Override
        protected Response doSync(ArtistData data)
        {
            if (response != null)
            {
                assertSame("Wrong artist", expArtist, data);
                Response resp = response;
                response = null;
                return resp;
            }
            throw new UnsupportedOperationException("Unsupported method call!");
        }
    }
}
