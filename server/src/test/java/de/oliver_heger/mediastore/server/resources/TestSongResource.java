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
import de.oliver_heger.mediastore.service.ObjectFactory;
import de.oliver_heger.mediastore.service.SongData;

/**
 * Test class for {@code SongResource}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSongResource
{
    /** The object factory. */
    private static ObjectFactory factory;

    /** The resource to be tested. */
    private SongResourceTestImpl resource;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception
    {
        factory = new ObjectFactory();
    }

    @Before
    public void setUp() throws Exception
    {
        resource = new SongResourceTestImpl();
    }

    /**
     * Tests whether the sync service is invoked correctly.
     */
    @Test
    public void testInvokeSyncService() throws NotLoggedInException
    {
        MediaSyncService service = EasyMock.createMock(MediaSyncService.class);
        @SuppressWarnings("unchecked")
        SyncResult<String> result = EasyMock.createMock(SyncResult.class);
        SongData data = factory.createSongData();
        EasyMock.expect(service.syncSong(data)).andReturn(result);
        EasyMock.replay(service, result);
        resource.invokeSycService(service, data);
        EasyMock.verify(service, result);
    }

    /**
     * Tests whether a PUT request for a song can be processed.
     */
    @Test
    public void testPutSong()
    {
        Response resp = Response.ok().build();
        SongData data = factory.createSongData();
        resource.expectDoSync(data, resp);
        assertSame("Wrong result", resp, resource.putSong(data));
    }

    /**
     * A test implementation of the resource class which allows mocking the
     * actual sync method.
     */
    private static class SongResourceTestImpl extends SongResource
    {
        /** The expected data object. */
        private SongData expData;

        /** The response to be returned. */
        private Response response;

        /**
         * Prepares this object to expect an invocation of doSync().
         *
         * @param data the expected data object
         * @param resp the response to be returned
         */
        public void expectDoSync(SongData data, Response resp)
        {
            expData = data;
            response = resp;
        }

        /**
         * Checks the parameters and returns the predefined response.
         */
        @Override
        protected Response doSync(SongData data)
        {
            if (response != null)
            {
                assertEquals("Wrong data object", expData, data);
                Response result = response;
                response = null;
                return result;
            }
            throw new IllegalStateException("Unexpected method call!");
        }
    }
}
