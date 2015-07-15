package de.oliver_heger.mediastore.server.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriBuilderException;
import javax.ws.rs.core.UriInfo;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import de.oliver_heger.mediastore.server.NotLoggedInException;
import de.oliver_heger.mediastore.server.sync.MediaSyncService;
import de.oliver_heger.mediastore.server.sync.MediaSyncServiceImpl;
import de.oliver_heger.mediastore.server.sync.SyncResult;
import de.oliver_heger.mediastore.service.ArtistData;
import de.oliver_heger.mediastore.service.ObjectFactory;

/**
 * Test class for {@code AbstractSyncResource}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestAbstractSyncResource
{
    /** Constant for the base URI. */
    private static final String BASE_URI =
            "http://www.remotemediastore.com/artist/";

    /** The resource to be tested. */
    private AbstractSyncResourceTestImpl resource;

    @Before
    public void setUp() throws Exception
    {
        resource = new AbstractSyncResourceTestImpl();
    }

    /**
     * Creates a mock sync result object.
     *
     * @return the mock object
     */
    private SyncResult<Long> createResultMock()
    {
        @SuppressWarnings("unchecked")
        SyncResult<Long> res = EasyMock.createMock(SyncResult.class);
        return res;
    }

    /**
     * Tests whether a sync service can be obtained.
     */
    @Test
    public void testFetchSyncService()
    {
        assertEquals("Wrong service class", MediaSyncServiceImpl.class,
                resource.fetchSyncService().getClass());
    }

    /**
     * Tests a successful synchronization operation.
     */
    @Test
    public void testDoSync()
    {
        MediaSyncService service = resource.initSyncServiceMock();
        EasyMock.replay(service);
        SyncResult<Long> res = createResultMock();
        ArtistData data = new ObjectFactory().createArtistData();
        Response resp = Response.status(Status.ACCEPTED).build();
        resource.initMockResponse(resp);
        resource.expectSync(data, res);
        assertSame("Wrong response", resp, resource.doSync(data));
    }

    /**
     * Tests doSync() if no user is logged in.
     */
    @Test
    public void testDoSyncNotLoggedIn()
    {
        resource.expectSyncNotLoggedIn();
        Response resp = resource.doSync(new ObjectFactory().createArtistData());
        assertEquals("Wrong status", Status.UNAUTHORIZED.getStatusCode(),
                resp.getStatus());
    }

    /**
     * Tests the response created for a successful sync operation.
     */
    @Test
    public void testResponseFromSyncResultCreated()
    {
        SyncResult<Long> res = createResultMock();
        UriInfo uinfo = EasyMock.createMock(UriInfo.class);
        final Long key = 1234567890L;
        EasyMock.expect(res.getKey()).andReturn(key);
        EasyMock.expect(res.imported()).andReturn(Boolean.TRUE);
        EasyMock.expect(uinfo.getAbsolutePathBuilder()).andReturn(
                new UriBuilderStub());
        EasyMock.replay(res, uinfo);
        resource.uriInfo = uinfo;
        Response resp = resource.createResponseFromSyncResult(res);
        assertEquals("Wrong status", Status.CREATED.getStatusCode(),
                resp.getStatus());
        List<Object> list = resp.getMetadata().get("Location");
        assertEquals("Wrong number of location headers", 1, list.size());
        assertEquals("Wrong location", BASE_URI + key,
                ((URI) list.get(0)).toString());
        EasyMock.verify(res, uinfo);
    }

    /**
     * Tests the response created for a sync operation if the data already
     * existed.
     */
    @Test
    public void testResponseFromSyncResultExisting()
    {
        SyncResult<Long> res = createResultMock();
        UriInfo uinfo = EasyMock.createMock(UriInfo.class);
        final Long key = 1234567890L;
        EasyMock.expect(res.getKey()).andReturn(key);
        EasyMock.expect(res.imported()).andReturn(Boolean.FALSE);
        EasyMock.expect(uinfo.getAbsolutePathBuilder()).andReturn(
                new UriBuilderStub());
        EasyMock.replay(res, uinfo);
        resource.uriInfo = uinfo;
        Response resp = resource.createResponseFromSyncResult(res);
        assertEquals("Wrong status", Status.NOT_MODIFIED.getStatusCode(),
                resp.getStatus());
        List<Object> list = resp.getMetadata().get("Location");
        assertEquals("Wrong number of location headers", 1, list.size());
        assertEquals("Wrong location", BASE_URI + key,
                ((URI) list.get(0)).toString());
        EasyMock.verify(res, uinfo);
    }

    /**
     * A test implementation of the abstract test class.
     */
    private static class AbstractSyncResourceTestImpl extends
            AbstractSyncResource<ArtistData, Long>
    {
        /** A mock for the sync service. */
        private MediaSyncService syncService;

        /** The result object to be returned by invokeSyncService(). */
        private SyncResult<Long> syncResult;

        /** The expected artist data object. */
        private ArtistData expArtist;

        /** A mock response object. */
        private Response response;

        /**
         * A flag whether an exception is to be thrown that no user is logged
         * in.
         */
        private boolean notLoggedIn;

        /**
         * Initializes a mock object for the sync service.
         *
         * @return the mock object
         */
        public MediaSyncService initSyncServiceMock()
        {
            syncService = EasyMock.createMock(MediaSyncService.class);
            return syncService;
        }

        /**
         * Prepares this object to expect a sync operation with the passed in
         * parameters.
         *
         * @param artist the artist data object
         * @param res the result of the service
         */
        public void expectSync(ArtistData artist, SyncResult<Long> res)
        {
            expArtist = artist;
            syncResult = res;
        }

        /**
         * Prepares this object to throw a not logged in exception when invoking
         * the sync service.
         */
        public void expectSyncNotLoggedIn()
        {
            notLoggedIn = true;
        }

        /**
         * Sets a mock response to be returned by the create response method.
         *
         * @param resp the response
         */
        public void initMockResponse(Response resp)
        {
            response = resp;
        }

        /**
         * Either returns the mock service or calls the super method.
         */
        @Override
        protected MediaSyncService fetchSyncService()
        {
            return (syncService != null) ? syncService : super
                    .fetchSyncService();
        }

        /**
         * Either returns the mock response or calls the super method.
         */
        @Override
        protected Response createResponseFromSyncResult(SyncResult<Long> result)
        {
            if (response != null)
            {
                assertSame("Wrong result", syncResult, result);
                return response;
            }
            return super.createResponseFromSyncResult(result);
        }

        /**
         * Checks the parameters and returns the mock result.
         */
        @Override
        protected SyncResult<Long> invokeSycService(MediaSyncService service,
                ArtistData data) throws NotLoggedInException
        {
            if (notLoggedIn)
            {
                throw new NotLoggedInException();
            }
            assertSame("Wrong service", syncService, service);
            assertSame("Wrong data", expArtist, data);
            return syncResult;
        }
    }

    /**
     * A stub implementation of UriBuilder used for testing whether URIs are
     * correctly constructed.
     */
    private static class UriBuilderStub extends UriBuilder
    {
        /** A buffer for storing the components that were appended. */
        private final StringBuilder buffer;

        public UriBuilderStub()
        {
            buffer = new StringBuilder(BASE_URI);
        }

        @Override
        public URI build(Object... args) throws IllegalArgumentException,
                UriBuilderException
        {
            for (Object o : args)
            {
                buffer.append(o);
            }
            try
            {
                return new URI(buffer.toString());
            }
            catch (URISyntaxException e)
            {
                throw new UriBuilderException(e);
            }
        }

        @Override
        public URI buildFromEncoded(Object... arg0)
                throws IllegalArgumentException, UriBuilderException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public URI buildFromEncodedMap(Map<String, ? extends Object> arg0)
                throws IllegalArgumentException, UriBuilderException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public URI buildFromMap(Map<String, ? extends Object> arg0)
                throws IllegalArgumentException, UriBuilderException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder clone()
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder fragment(String arg0)
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder host(String arg0) throws IllegalArgumentException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder matrixParam(String arg0, Object... arg1)
                throws IllegalArgumentException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder path(String arg0) throws IllegalArgumentException
        {
            buffer.append(arg0);
            return this;
        }

        @Override
        public UriBuilder path(@SuppressWarnings("rawtypes") Class arg0)
                throws IllegalArgumentException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder path(Method arg0) throws IllegalArgumentException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder path(@SuppressWarnings("rawtypes") Class arg0,
                String arg1) throws IllegalArgumentException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder port(int arg0) throws IllegalArgumentException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder queryParam(String arg0, Object... arg1)
                throws IllegalArgumentException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder replaceMatrix(String arg0)
                throws IllegalArgumentException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder replaceMatrixParam(String arg0, Object... arg1)
                throws IllegalArgumentException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder replacePath(String arg0)
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder replaceQuery(String arg0)
                throws IllegalArgumentException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder replaceQueryParam(String arg0, Object... arg1)
                throws IllegalArgumentException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder scheme(String arg0) throws IllegalArgumentException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder schemeSpecificPart(String arg0)
                throws IllegalArgumentException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder segment(String... arg0)
                throws IllegalArgumentException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder uri(URI arg0) throws IllegalArgumentException
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }

        @Override
        public UriBuilder userInfo(String arg0)
        {
            throw new UnsupportedOperationException("Unexpected method call!");
        }
    }
}
