package de.oliver_heger.mediastore.localstore.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.ws.rs.core.MediaType;

import org.apache.commons.lang3.concurrent.ConcurrentInitializer;
import org.apache.commons.lang3.concurrent.ConstantInitializer;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.UniformInterface;
import com.sun.jersey.api.client.WebResource;

import de.oliver_heger.mediastore.localstore.SyncController;
import de.oliver_heger.mediastore.localstore.model.AlbumEntity;
import de.oliver_heger.mediastore.localstore.model.ArtistEntity;
import de.oliver_heger.mediastore.localstore.model.SongEntity;
import de.oliver_heger.mediastore.oauth.NotAuthorizedException;
import de.oliver_heger.mediastore.oauth.OAuthCallback;
import de.oliver_heger.mediastore.oauth.OAuthTemplate;
import de.oliver_heger.mediastore.service.AlbumData;
import de.oliver_heger.mediastore.service.ArtistData;
import de.oliver_heger.mediastore.service.ObjectFactory;
import de.oliver_heger.mediastore.service.SongData;

/**
 * Test class for {@code SyncSongCommand}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestSyncSongCommand
{
    /** Constant for the ID of the test song entity. */
    private static final Long SONG_ID = 20110413215308L;

    /** Constant for a test sub path of a resource. */
    private static final String PATH = "song";

    /** A mock entity manager factory. */
    private EntityManagerFactory emf;

    /** An initializer for the EMF. */
    private ConcurrentInitializer<EntityManagerFactory> init;

    /** A mock sync controller. */
    private SyncController controller;

    /** A mock OAuth template. */
    private OAuthTemplate templ;

    /** A test song entity. */
    private SongEntity song;

    /** The object factory. */
    private ObjectFactory factory;

    @Before
    public void setUp() throws Exception
    {
        emf = EasyMock.createMock(EntityManagerFactory.class);
        init = new ConstantInitializer<EntityManagerFactory>(emf);
        controller = EasyMock.createMock(SyncController.class);
        templ = EasyMock.createMock(OAuthTemplate.class);
        song = createSong();
        factory = new ObjectFactory();
    }

    /**
     * Creates a test command with default settings.
     *
     * @return the test command
     */
    private SyncSongCommand createTestCommand()
    {
        return new SyncSongCommand(init, song, controller, templ);
    }

    /**
     * Creates a test song entity object.
     *
     * @return the test entity
     */
    private static SongEntity createSong()
    {
        SongEntity song = new SongEntity()
        {
            private static final long serialVersionUID = 1L;

            @Override
            public Long getId()
            {
                return SONG_ID;
            }
        };
        song.setCurrentPlayCount(5);
        song.setDuration(100);
        song.setInceptionYear(2010);
        song.setName("Angle Dance");
        song.setTotalPlayCount(10);
        song.setTrackNo(1);
        AlbumEntity album = new AlbumEntity();
        album.setInceptionYear(2010);
        album.setName("Band of Joy");
        album.addSong(song);
        ArtistEntity artist = new ArtistEntity();
        artist.setName("Robert Plant");
        artist.addSong(song);
        return song;
    }

    /**
     * Tests whether a correct album data object is created.
     */
    @Test
    public void testCreateAlbumData()
    {
        SyncSongCommand cmd = createTestCommand();
        AlbumData data = cmd.createAlbumData();
        AlbumEntity album = song.getAlbum();
        assertEquals("Wrong album name", album.getName(), data.getName());
        assertEquals("Wrong inception year", album.getInceptionYear()
                .intValue(), data.getInceptionYear());
    }

    /**
     * Tests createAlbumData() if the song does not reference an album.
     */
    @Test
    public void testCreateAlbumDataNoAlbum()
    {
        song.setAlbum(null);
        SyncSongCommand cmd = createTestCommand();
        assertNull("Got an album data object", cmd.createAlbumData());
    }

    /**
     * Tests whether a correct artist data object is created.
     */
    @Test
    public void testCreateArtistData()
    {
        SyncSongCommand cmd = createTestCommand();
        ArtistData data = cmd.createArtistData();
        ArtistEntity artist = song.getArtist();
        assertEquals("Wrong artist name", artist.getName(), data.getName());
    }

    /**
     * Tests createArtistData() if the song does not contain an artist
     * reference.
     */
    @Test
    public void testCreateArtistDataNoArtist()
    {
        song.setArtist(null);
        SyncSongCommand cmd = createTestCommand();
        assertNull("Got an artist data object", cmd.createArtistData());
    }

    /**
     * Tests whether a correct song data object is created.
     */
    @Test
    public void testFetchSongData()
    {
        SyncSongCommand cmd = createTestCommand();
        SongData data = cmd.fetchSongData();
        assertEquals("Wrong song name", song.getName(), data.getName());
        assertEquals("Wrong duration", song.getDuration().longValue() * 1000,
                data.getDuration().longValue());
        assertEquals("Wrong play count", song.getCurrentPlayCount(),
                data.getPlayCount());
        assertEquals("Wrong track number", song.getTrackNo().intValue(), data
                .getTrackNo().intValue());
        assertEquals("Wrong inception year",
                song.getInceptionYear().intValue(), data.getInceptionYear()
                        .intValue());
        assertEquals("Wrong album name", song.getAlbum().getName(),
                data.getAlbumName());
        assertEquals("Wrong artist name", song.getArtist().getName(),
                data.getArtistName());
    }

    /**
     * Tests whether a song data object can be created if only a minimum set of
     * properties is defined.
     */
    @Test
    public void testFetchSongDataMinimum()
    {
        SongEntity s = new SongEntity();
        s.setName(song.getName());
        SyncSongCommand cmd = new SyncSongCommand(init, s, controller, templ);
        SongData data = cmd.fetchSongData();
        assertNull("Got a duration", data.getDuration());
        assertNull("Got a track number", data.getTrackNo());
        assertNull("Got a year", data.getInceptionYear());
        assertNull("Got an album name", data.getAlbumName());
        assertNull("Got an artist name", data.getArtistName());
    }

    /**
     * Tests whether always the same song data object is returned.
     */
    @Test
    public void testFetchSongDataCached()
    {
        SyncSongCommand cmd = createTestCommand();
        SongData data = cmd.fetchSongData();
        assertSame("Multiple instances", data, cmd.fetchSongData());
    }

    /**
     * Tests processResponse(). We can only check that this method does not
     * touch the response object.
     */
    @Test
    public void testProcessResponse() throws NotAuthorizedException
    {
        ClientResponse resp = EasyMock.createMock(ClientResponse.class);
        EasyMock.replay(resp);
        SyncSongCommand cmd = createTestCommand();
        cmd.processResponse(resp);
        EasyMock.verify(resp);
    }

    /**
     * Tests whether a resource is correctly initialized.
     */
    @Test
    public void testPrepareResource()
    {
        WebResource resource = EasyMock.createMock(WebResource.class);
        EasyMock.expect(resource.path(PATH)).andReturn(resource);
        EasyMock.expect(resource.accept(MediaType.APPLICATION_XML)).andReturn(
                null);
        EasyMock.replay(resource);
        SyncSongCommand cmd = createTestCommand();
        assertNull("Wrong result", cmd.prepareResource(resource, PATH));
        EasyMock.verify(resource);
    }

    /**
     * Tests syncRequest() if no data object is available.
     */
    @Test
    public void testSyncRequestNoData() throws NotAuthorizedException
    {
        WebResource resource = EasyMock.createMock(WebResource.class);
        EasyMock.replay(resource);
        SyncSongCommand cmd = createTestCommand();
        assertFalse("Wrong result", cmd.syncRequest(resource, PATH, null));
        EasyMock.verify(resource);
    }

    /**
     * Creates a mock response object that returns the specified status.
     *
     * @param status the status
     * @return the mock response
     */
    private ClientResponse createResponseMock(ClientResponse.Status status)
    {
        ClientResponse resp = EasyMock.createMock(ClientResponse.class);
        EasyMock.expect(resp.getStatus()).andReturn(status.getStatusCode())
                .anyTimes();
        return resp;
    }

    /**
     * Helper method for testing a successful sync request.
     *
     * @param status the status of the response
     * @param expResult the expected result of the method
     * @throws NotAuthorizedException if an authorization error occurs
     */
    private void checkSyncRequestCallSuccess(ClientResponse.Status status,
            boolean expResult) throws NotAuthorizedException
    {
        WebResource resource = EasyMock.createMock(WebResource.class);
        ClientResponse resp = createResponseMock(status);
        SyncSongCommandRequestTestImpl cmd =
                new SyncSongCommandRequestTestImpl(init, song, controller,
                        templ, resource);
        EasyMock.expect(cmd.getBuilder().put(ClientResponse.class, this))
                .andReturn(resp);
        EasyMock.replay(resource, cmd.getBuilder(), resp);
        assertEquals("Wrong result", expResult,
                cmd.syncRequest(resource, PATH, this));
        EasyMock.verify(resource, cmd.getBuilder(), resp);
    }

    /**
     * Tests a sync request if a new object is created on the server.
     */
    @Test
    public void testSyncRequestCreated() throws NotAuthorizedException
    {
        checkSyncRequestCallSuccess(ClientResponse.Status.CREATED, true);
    }

    /**
     * Tests a sync request if the object already existed on the server.
     */
    @Test
    public void testSyncRequestNotCreated() throws NotAuthorizedException
    {
        checkSyncRequestCallSuccess(ClientResponse.Status.NO_CONTENT, false);
    }

    /**
     * Tests a request which is not authorized.
     */
    @Test
    public void testSyncRequestNotAuthorized() throws NotAuthorizedException
    {
        WebResource resource = EasyMock.createMock(WebResource.class);
        SyncSongCommandRequestTestImpl cmd =
                new SyncSongCommandRequestTestImpl(init, song, controller,
                        templ, resource);
        ClientResponse resp =
                createResponseMock(ClientResponse.Status.UNAUTHORIZED);
        EasyMock.expect(cmd.getBuilder().put(ClientResponse.class, this))
                .andReturn(resp);
        EasyMock.replay(resource, cmd.getBuilder(), resp);
        try
        {
            cmd.syncRequest(resource, PATH, this);
            fail("Unauthorized request not detected!");
        }
        catch (NotAuthorizedException nex)
        {
            EasyMock.verify(resource, cmd.getBuilder(), resp);
        }
    }

    /**
     * Tests whether an artist can be synchronized.
     */
    @Test
    public void testSyncArtist() throws NotAuthorizedException
    {
        WebResource resource = EasyMock.createMock(WebResource.class);
        EasyMock.replay(resource);
        ArtistData data = factory.createArtistData();
        SyncSongCommandMockRequestImpl cmd =
                new SyncSongCommandMockRequestImpl(resource, "artist", data);
        assertTrue("Wrong result", cmd.syncArtist(resource));
        cmd.verify();
        EasyMock.verify(resource);
    }

    /**
     * Tests whether an album can be synchronized.
     */
    @Test
    public void testSyncAlbum() throws NotAuthorizedException
    {
        WebResource resource = EasyMock.createMock(WebResource.class);
        EasyMock.replay(resource);
        AlbumData data = factory.createAlbumData();
        SyncSongCommandMockRequestImpl cmd =
                new SyncSongCommandMockRequestImpl(resource, "album", data);
        assertTrue("Wrong result", cmd.syncAlbum(resource));
        cmd.verify();
        EasyMock.verify(resource);
    }

    /**
     * Tests whether a song can be synchronized.
     */
    @Test
    public void testSyncSong() throws NotAuthorizedException
    {
        WebResource resource = EasyMock.createMock(WebResource.class);
        EasyMock.replay(resource);
        SongData data = factory.createSongData();
        SyncSongCommandMockRequestImpl cmd =
                new SyncSongCommandMockRequestImpl(resource, "song", data);
        assertTrue("Wrong result", cmd.syncSong(resource, data));
        cmd.verify();
        EasyMock.verify(resource);
    }

    /**
     * Tests a successful sync operation.
     */
    @Test
    public void testExecuteSyncOperationSuccess()
    {
        OAuthCallback callback = EasyMock.createMock(OAuthCallback.class);
        SongData data = factory.createSongData();
        EasyMock.expect(controller.beforeSongSync(data))
                .andReturn(Boolean.TRUE);
        EasyMock.expect(controller.getOAuthCallback()).andReturn(callback);
        SyncSongCommand cmd = new SyncSongCommandMockSongData(data);
        EasyMock.expect(templ.execute(cmd, callback)).andReturn(Boolean.TRUE);
        EasyMock.replay(callback, controller, templ);
        assertTrue("Wrong result", cmd.executeSyncOperation());
        EasyMock.verify(callback, controller, templ);
    }

    /**
     * Tests whether the sync controller can skip the synchronization of a
     * specific song.
     */
    @Test
    public void testExecuteSyncOperationSkip()
    {
        SongData data = factory.createSongData();
        EasyMock.expect(controller.beforeSongSync(data)).andReturn(
                Boolean.FALSE);
        EasyMock.replay(controller, templ);
        SyncSongCommand cmd = new SyncSongCommandMockSongData(data);
        assertFalse("Wrong result", cmd.executeSyncOperation());
        EasyMock.verify(controller, templ);
    }

    /**
     * Tests whether the sync controller is properly informed about a failed
     * OAuth authorization.
     */
    @Test
    public void testExecuteSyncOperationOAuthAborted()
    {
        OAuthCallback callback = EasyMock.createMock(OAuthCallback.class);
        SongData data = factory.createSongData();
        EasyMock.expect(controller.beforeSongSync(data))
                .andReturn(Boolean.TRUE);
        EasyMock.expect(controller.getOAuthCallback()).andReturn(callback);
        SyncSongCommand cmd = new SyncSongCommandMockSongData(data);
        EasyMock.expect(templ.execute(cmd, callback)).andReturn(Boolean.FALSE);
        controller.authorizationFailed(data);
        EasyMock.replay(callback, controller, templ);
        assertFalse("Wrong result", cmd.executeSyncOperation());
        EasyMock.verify(callback, controller, templ);
    }

    /**
     * Tests whether exceptions are reported to the sync controller.
     */
    @Test
    public void testOnException()
    {
        SongData data = factory.createSongData();
        Throwable ex = new RuntimeException("Test exception!");
        controller.failedSongSync(data, ex);
        EasyMock.replay(controller, templ);
        SyncSongCommand cmd = new SyncSongCommandMockSongData(data);
        cmd.onException(ex);
        EasyMock.verify(controller, templ);
    }

    /**
     * Tests whether the resource processor implementation works correctly.
     */
    @Test
    public void testDoWithResource() throws NotAuthorizedException
    {
        final WebResource webResource = EasyMock.createMock(WebResource.class);
        final SongData data = factory.createSongData();
        final StringBuilder bufMethods = new StringBuilder();
        SyncSongCommand cmd = new SyncSongCommandMockSongData(data)
        {

            @Override
            boolean syncArtist(WebResource resource)
                    throws NotAuthorizedException
            {
                checkResource(resource);
                bufMethods.append("artist");
                return true;
            }

            @Override
            boolean syncAlbum(WebResource resource)
                    throws NotAuthorizedException
            {
                checkResource(resource);
                bufMethods.append("album");
                return false;
            }

            @Override
            boolean syncSong(WebResource resource, SongData songData)
                    throws NotAuthorizedException
            {
                checkResource(resource);
                assertSame("Wrong song data", data, songData);
                bufMethods.append("song");
                return true;
            }

            private void checkResource(WebResource resource)
            {
                assertSame("Wrong resource", webResource, resource);
            }
        };
        controller.afterSongSync(data, true, true, false);
        EasyMock.replay(webResource, controller, templ);
        assertNull("Wrong result", cmd.doWithResource(webResource));
        assertEquals("Wrong methods", "artistalbumsong", bufMethods.toString());
        EasyMock.verify(webResource, controller, templ);
    }

    /**
     * Tests whether the expected JPA operation is executed.
     */
    @Test
    public void testExecuteJPAOperation()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        SongEntity e = EasyMock.createMock(SongEntity.class);
        EasyMock.expect(em.find(SongEntity.class, SONG_ID)).andReturn(e);
        e.resetCurrentCount();
        EasyMock.replay(em, e);
        SyncSongCommand cmd = createTestCommand();
        cmd.executeJPAOperation(em);
        EasyMock.verify(em, e);
    }

    /**
     * Tests executeJPAOperation() if the entity cannot be found.
     */
    @Test
    public void testExecuteJPAOperationEntityNotFound()
    {
        EntityManager em = EasyMock.createMock(EntityManager.class);
        EasyMock.expect(em.find(SongEntity.class, SONG_ID)).andReturn(null);
        EasyMock.replay(em);
        SyncSongCommand cmd = createTestCommand();
        cmd.executeJPAOperation(em);
        EasyMock.verify(em);
    }

    /**
     * Tests a complete command execution.
     */
    @Test
    public void testExecuteFull() throws Exception
    {
        EntityManager em = EasyMock.createNiceMock(EntityManager.class);
        EntityTransaction tx = EasyMock.createNiceMock(EntityTransaction.class);
        EasyMock.expect(em.getTransaction()).andReturn(tx).anyTimes();
        EasyMock.expect(emf.createEntityManager()).andReturn(em);
        EasyMock.replay(emf, em, tx);
        SyncSongCommandkExecuteImpl cmd =
                new SyncSongCommandkExecuteImpl(em, true);
        cmd.execute();
        cmd.verify("executeSyncOperation(), executeJPAOperation()");
        EasyMock.verify(emf, em);
    }

    /**
     * Tests the execution of the command if the sync operation was not
     * successful.
     */
    @Test
    public void testExecuteNoSync() throws Exception
    {
        EasyMock.replay(emf);
        SyncSongCommandkExecuteImpl cmd =
                new SyncSongCommandkExecuteImpl(null, false);
        cmd.execute();
        cmd.verify("executeSyncOperation()");
        EasyMock.verify(emf);
    }

    /**
     * Tests whether the inception year 0 is correctly handled. (This year may
     * occur in legacy data.)
     */
    @Test
    public void testCreateSongDataInceptionYear0()
    {
        song.setInceptionYear(0);
        SyncSongCommand cmd = createTestCommand();
        SongData data = cmd.fetchSongData();
        assertNull("Got an inception year", data.getInceptionYear());
    }

    /**
     * A test implementation for testing whether sync requests are correctly
     * handled.
     */
    private static class SyncSongCommandRequestTestImpl extends SyncSongCommand
    {
        /** The expected resource object. */
        private final WebResource resource;

        /** A mock builder object. */
        private final UniformInterface builder;

        public SyncSongCommandRequestTestImpl(
                ConcurrentInitializer<EntityManagerFactory> emfInit,
                SongEntity songEntity, SyncController ctrl,
                OAuthTemplate templ, WebResource res)
        {
            super(emfInit, songEntity, ctrl, templ);
            resource = res;
            builder = EasyMock.createMock(UniformInterface.class);
        }

        /**
         * Returns the mock builder object.
         *
         * @return the mock builder
         */
        public UniformInterface getBuilder()
        {
            return builder;
        }

        /**
         * Checks the parameters and returns the mock builder.
         */
        @Override
        UniformInterface prepareResource(WebResource res, String path)
        {
            assertSame("Wrong resource", resource, res);
            assertEquals("Wrong path", PATH, path);
            return getBuilder();
        }
    }

    /**
     * A specialized test implementation which allows mocking the syncRequest()
     * method.
     */
    private class SyncSongCommandMockRequestImpl extends SyncSongCommand
    {
        /** The expected resource. */
        private final WebResource resource;

        /** The expected path. */
        private final String path;

        /** The expected data object. */
        private final Object data;

        /** A counter for sync operations. */
        private int syncCalls;

        /**
         * Creates a new instance of {@code SyncSongCommandMockRequestImpl} and
         * sets the expected test objects.
         *
         * @param expRes the expected resource
         * @param expPath the expected path
         * @param expData the expected data object
         */
        public SyncSongCommandMockRequestImpl(WebResource expRes,
                String expPath, Object expData)
        {
            super(init, song, controller, templ);
            resource = expRes;
            path = expPath;
            data = expData;
        }

        /**
         * Verifies that the sync operation was called once.
         */
        public void verify()
        {
            assertEquals("Wrong number of sync calls", 1, syncCalls);
        }

        /**
         * Checks the parameters.
         */
        @Override
        boolean syncRequest(WebResource resource, String path, Object data)
                throws NotAuthorizedException
        {
            assertSame("Wrong resource", this.resource, resource);
            assertEquals("Wrong path", this.path, path);
            assertEquals("Wrong data", this.data, data);
            syncCalls++;
            return true;
        }

        /**
         * Checks the type of the expected object and returns it if it fits.
         */
        @Override
        AlbumData createAlbumData()
        {
            return (AlbumData) data;
        }

        /**
         * Checks the type of the expected object and returns it if it fits.
         */
        @Override
        ArtistData createArtistData()
        {
            return (ArtistData) data;
        }

        /**
         * Checks the type of the expected object and returns it if it fits.
         */
        @Override
        SongData createSongData()
        {
            return (SongData) data;
        }
    }

    /**
     * A test command implementation which allows mocking the createSongData()
     * method.
     */
    private class SyncSongCommandMockSongData extends SyncSongCommand
    {
        /** The mock song data object. */
        private final SongData songData;

        /**
         * Creates a new instance of {@code SyncSongCommandMockSongData}.
         *
         * @param data the mock song data object
         */
        public SyncSongCommandMockSongData(SongData data)
        {
            super(init, song, controller, templ);
            songData = data;
        }

        /**
         * Always returns the mock data object.
         */
        @Override
        SongData fetchSongData()
        {
            return songData;
        }
    }

    /**
     * A test command implementation for testing the execute() method.
     */
    private class SyncSongCommandkExecuteImpl extends SyncSongCommand
    {
        /** The expected entity manager. */
        private final EntityManager expEM;

        /** A string buffer for the methods that are invoked. */
        private final StringBuilder bufMethods;

        /** The result of the executeSyncOperation() method. */
        private final boolean syncResult;

        /**
         * Creates a new instance of {@code SyncSongCommandkExecuteImpl} and
         * sets the expected entity manager and the result of the sync
         * operation.
         *
         * @param em the entity manager
         * @param syncres the sync result
         */
        public SyncSongCommandkExecuteImpl(EntityManager em, boolean syncres)
        {
            super(init, song, controller, templ);
            expEM = em;
            syncResult = syncres;
            bufMethods = new StringBuilder();
        }

        /**
         * Verifies that the expected methods have been called.
         *
         * @param expMethods the expected methods as string
         */
        public void verify(String expMethods)
        {
            assertEquals("Wrong methods", expMethods, bufMethods.toString());
        }

        /**
         * Checks the parameters and records this invocation.
         */
        @Override
        protected void executeJPAOperation(EntityManager em)
        {
            assertSame("Wrong entity manager", expEM, em);
            bufMethods.append(", executeJPAOperation()");
        }

        /**
         * Records this invocation.
         */
        @Override
        boolean executeSyncOperation()
        {
            bufMethods.append("executeSyncOperation()");
            return syncResult;
        }
    }
}
