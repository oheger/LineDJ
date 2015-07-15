package de.oliver_heger.mediastore.server.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import javax.ws.rs.core.Response;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import de.oliver_heger.mediastore.server.NotLoggedInException;
import de.oliver_heger.mediastore.server.sync.MediaSyncService;
import de.oliver_heger.mediastore.server.sync.SyncResult;
import de.oliver_heger.mediastore.service.AlbumData;
import de.oliver_heger.mediastore.service.ObjectFactory;

/**
 * Test class for {@code AlbumResource}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAlbumResource
{
    /** The object factory. */
    private static ObjectFactory factory;

    /** The resource to be tested. */
    private AlbumResourceTestImpl resource;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception
    {
        factory = new ObjectFactory();
    }

    @Before
    public void setUp() throws Exception
    {
        resource = new AlbumResourceTestImpl();
    }

    /**
     * Tests processing of a put request.
     */
    @Test
    public void testPutAlbum()
    {
        Response resp = Response.ok().build();
        AlbumData data = factory.createAlbumData();
        resource.expectDoSync(data, resp);
        assertSame("Wrong response", resp, resource.putAlbum(data));
    }

    /**
     * Tests whether the sync service is invoked correctly.
     */
    @Test
    public void testInvokeSyncService() throws NotLoggedInException
    {
        MediaSyncService service = EasyMock.createMock(MediaSyncService.class);
        @SuppressWarnings("unchecked")
        SyncResult<Long> result = EasyMock.createMock(SyncResult.class);
        AlbumData data = factory.createAlbumData();
        EasyMock.expect(service.syncAlbum(data)).andReturn(result);
        EasyMock.replay(service, result);
        assertSame("Wrong result", result,
                resource.invokeSycService(service, data));
        EasyMock.verify(service, result);
    }

    /**
     * A test implementation of the album resource which allows mocking the
     * actual synchronization method.
     */
    private static class AlbumResourceTestImpl extends AlbumResource
    {
        /** The expected album data object. */
        private AlbumData expData;

        /** The response to be returned. */
        private Response response;

        /**
         * Prepares this object to expect an invocation of doSync().
         *
         * @param data the expected data object
         * @param resp the response to be returned
         */
        public void expectDoSync(AlbumData data, Response resp)
        {
            expData = data;
            response = resp;
        }

        /**
         * Checks the arguments and returns the predefined response.
         */
        @Override
        protected Response doSync(AlbumData data)
        {
            if (response != null)
            {
                assertEquals("Wrong album", expData, data);
                Response result = response;
                response = null;
                return result;
            }
            throw new UnsupportedOperationException("Unexpected method call!");
        }
    }
}
