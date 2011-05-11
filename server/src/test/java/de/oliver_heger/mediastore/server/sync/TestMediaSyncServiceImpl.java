package de.oliver_heger.mediastore.server.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigInteger;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.oauth.OAuthRequestException;
import com.google.appengine.api.oauth.OAuthService;
import com.google.appengine.api.users.User;
import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;

import de.oliver_heger.mediastore.server.NotLoggedInException;
import de.oliver_heger.mediastore.server.model.AlbumEntity;
import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.server.model.SongEntity;
import de.oliver_heger.mediastore.service.AlbumData;
import de.oliver_heger.mediastore.service.ArtistData;
import de.oliver_heger.mediastore.service.ObjectFactory;
import de.oliver_heger.mediastore.service.SongData;
import de.oliver_heger.mediastore.shared.persistence.PersistenceTestHelper;

/**
 * Test class for {@code MediaSyncServiceImpl}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestMediaSyncServiceImpl
{
    /** Constant for the name of an entity to be synchronized. */
    private static final String ENTITY_NAME = "TestName";

    /** Constant for an inception year. */
    private static final Integer INCEPTION_YEAR = 2011;

    /** Constant for the duration of a song. */
    private static final Long DURATION = (5 * 60 + 30) * 1000L;

    /** The object factory. */
    private static ObjectFactory factory;

    /** The test helper. */
    private final PersistenceTestHelper helper = new PersistenceTestHelper(
            new LocalDatastoreServiceTestConfig());

    /** The service to be tested. */
    private MediaSyncServiceTestImpl service;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception
    {
        factory = new ObjectFactory();
    }

    @Before
    public void setUp()
    {
        helper.setUp();
        service = new MediaSyncServiceTestImpl();
    }

    @After
    public void tearDown()
    {
        helper.tearDown();
    }

    /**
     * Tests whether a user can be authenticated successfully.
     */
    @Test
    public void testAuthenticateUserSuccess() throws NotLoggedInException,
            OAuthRequestException
    {
        OAuthService oauth = service.installMockOAuthService();
        User usr =
                PersistenceTestHelper.getUser(PersistenceTestHelper.OTHER_USER);
        EasyMock.expect(oauth.getCurrentUser()).andReturn(usr);
        EasyMock.replay(oauth);
        service.setCurrentUser(null);
        assertSame("Wrong user", usr, service.authenticateUser());
        EasyMock.verify(oauth);
    }

    /**
     * Tests whether a failed authentication is handled properly.
     */
    @Test
    public void testAuthenticateUserFailure() throws NotLoggedInException,
            OAuthRequestException
    {
        OAuthService oauth = service.installMockOAuthService();
        OAuthRequestException ex = new OAuthRequestException("TestException");
        EasyMock.expect(oauth.getCurrentUser()).andThrow(ex);
        EasyMock.replay(oauth);
        service.setCurrentUser(null);
        try
        {
            service.authenticateUser();
            fail("No exception for authentication failure!");
        }
        catch (NotLoggedInException nlex)
        {
            assertEquals("Wrong cause", ex, nlex.getCause());
        }
        EasyMock.verify(oauth);
    }

    /**
     * Tests whether a new artist is added to the database.
     */
    @Test
    public void testSyncArtistNew() throws NotLoggedInException
    {
        ArtistData data = factory.createArtistData();
        data.setName(ENTITY_NAME);
        SyncResult<Long> res = service.syncArtist(data);
        assertTrue("Not added", res.imported());
        ArtistEntity entity =
                helper.getEM().find(ArtistEntity.class, res.getKey());
        assertEquals("Wrong artist name", ENTITY_NAME, entity.getName());
        assertEquals("Wrong user", PersistenceTestHelper.getTestUser(),
                entity.getUser());
        PersistenceTestHelper.checkCurrentDate(entity.getCreationDate());
    }

    /**
     * Tests whether an already existing artist is handled correctly.
     */
    @Test
    public void testSyncArtistExisting() throws NotLoggedInException
    {
        ArtistEntity entity = new ArtistEntity();
        entity.setUser(service.getCurrentUser());
        entity.setName(ENTITY_NAME);
        helper.persist(entity);
        helper.closeEM();
        ArtistData data = factory.createArtistData();
        data.setName(ENTITY_NAME);
        SyncResult<Long> res = service.syncArtist(data);
        assertFalse("Added", res.imported());
        assertEquals("Wrong key", entity.getId(), res.getKey());
    }

    /**
     * Helper method for testing a sync operation if a new album has to be
     * added.
     *
     * @return the album entity
     * @throws NotLoggedInException if no user is logged in
     */
    private AlbumEntity checkSyncAlbumAdded() throws NotLoggedInException
    {
        AlbumData data = factory.createAlbumData();
        data.setName(ENTITY_NAME);
        data.setInceptionYear(INCEPTION_YEAR);
        SyncResult<Long> res = service.syncAlbum(data);
        assertTrue("Not added", res.imported());
        AlbumEntity entity =
                helper.getEM().find(AlbumEntity.class, res.getKey());
        assertEquals("Wrong name", ENTITY_NAME, entity.getName());
        assertEquals("Wrong user", PersistenceTestHelper.getTestUser(),
                entity.getUser());
        assertEquals("Wrong inception year", INCEPTION_YEAR,
                entity.getInceptionYear());
        PersistenceTestHelper.checkCurrentDate(entity.getCreationDate());
        return entity;
    }

    /**
     * Creates an album entity and associates it with a song with the given
     * inception year.
     *
     * @param name the album name
     * @param user the user (can be <b>null</b>, then the default user is used)
     * @param year the year
     * @return the album entity
     */
    private AlbumEntity persistAlbum(String name, User user, Integer year)
    {
        AlbumEntity album = new AlbumEntity();
        album.setName(name);
        album.setUser((user != null) ? user : PersistenceTestHelper
                .getTestUser());
        album.setInceptionYear(year);
        helper.persist(album);
        return album;
    }

    /**
     * Tests synchronization of an album if no match is found.
     */
    @Test
    public void testSyncAlbumNew() throws NotLoggedInException
    {
        checkSyncAlbumAdded();
    }

    /**
     * Tests whether the inception year is taken into account when synchronizing
     * an album.
     */
    @Test
    public void testSyncAlbumMatchingName() throws NotLoggedInException
    {
        final int year = INCEPTION_YEAR.intValue();
        for (int i = 2000; i < year; i++)
        {
            persistAlbum(ENTITY_NAME, null, i);
        }
        persistAlbum(
                ENTITY_NAME,
                PersistenceTestHelper.getUser(PersistenceTestHelper.OTHER_USER),
                INCEPTION_YEAR);
        checkSyncAlbumAdded();
    }

    /**
     * Tests whether a missing inception year is handled correctly when
     * synchronizing an album.
     */
    @Test
    public void testSyncAlbumNoInceptionYear() throws NotLoggedInException
    {
        persistAlbum(ENTITY_NAME, null, INCEPTION_YEAR);
        AlbumData data = factory.createAlbumData();
        data.setName(ENTITY_NAME);
        SyncResult<Long> result = service.syncAlbum(data);
        assertTrue("Not imported", result.imported());
        AlbumEntity entity =
                helper.getEM().find(AlbumEntity.class, result.getKey());
        assertNull("Got an inception year", entity.getInceptionYear());
    }

    /**
     * Tests a sync operation if a matching album can be found.
     */
    @Test
    public void testSyncAlbumExisting() throws NotLoggedInException
    {
        AlbumEntity album = persistAlbum(ENTITY_NAME, null, INCEPTION_YEAR);
        AlbumData data = factory.createAlbumData();
        data.setName(ENTITY_NAME);
        data.setInceptionYear(INCEPTION_YEAR);
        SyncResult<Long> result = service.syncAlbum(data);
        assertFalse("Imported", result.imported());
        assertEquals("Wrong ID", album.getId(), result.getKey());
    }

    /**
     * Helper method for creating and saving a song entity.
     *
     * @param name the song name
     * @param user the user (<b>null</b> for the default user)
     * @param duration the duration (may be <b>null</b>)
     * @param art the associated artist (may be <b>null</b>)
     */
    private SongEntity persistSong(String name, User user, Long duration,
            ArtistEntity art)
    {
        SongEntity song = new SongEntity();
        song.setName(name);
        song.setUser((user == null) ? PersistenceTestHelper.getTestUser()
                : user);
        song.setDuration(duration);
        if (art != null)
        {
            song.setArtistID(art.getId());
        }
        helper.persist(song);
        return song;
    }

    /**
     * Helper method for testing a sync operation with a song that has to be
     * added.
     *
     * @param data the data object
     * @return the added entity
     * @throws NotLoggedInException if an error occurs
     */
    private SongEntity checkSyncSongAdded(SongData data)
            throws NotLoggedInException
    {
        initSongData(data);
        SyncResult<String> result = service.syncSong(data);
        assertTrue("Not imported", result.imported());
        Key key = KeyFactory.stringToKey(result.getKey());
        SongEntity song = helper.getEM().find(SongEntity.class, key);
        assertEquals("Wrong album name", ENTITY_NAME, song.getName());
        assertEquals("Wrong user", PersistenceTestHelper.getTestUser(),
                song.getUser());
        assertEquals("Wrong inception year", INCEPTION_YEAR,
                song.getInceptionYear());
        assertEquals("Wrong duration", DURATION, song.getDuration());
        assertEquals("Wrong track number", 2, song.getTrackNo().intValue());
        assertEquals("Wrong play count", data.getPlayCount(),
                song.getPlayCount());
        return song;
    }

    /**
     * Initializes the given song data object with default values.
     *
     * @param data the data object to be filled
     */
    private void initSongData(SongData data)
    {
        data.setName(ENTITY_NAME);
        data.setDuration(BigInteger.valueOf(DURATION.longValue()));
        data.setInceptionYear(BigInteger.valueOf(INCEPTION_YEAR.longValue()));
        data.setTrackNo(BigInteger.valueOf(2));
        data.setPlayCount(5);
    }

    /**
     * Tests a sync operation for a new song if there are no references to an
     * album or artist.
     */
    @Test
    public void testSyncSongNewNoReferences() throws NotLoggedInException
    {
        SongData data = factory.createSongData();
        SongEntity song = checkSyncSongAdded(data);
        assertNull("Got an album ID", song.getAlbumID());
        assertNull("Got an artist ID", song.getArtistID());
    }

    /**
     * Tests a sync operation for a new song if there are no references, but
     * existing similar song entities.
     */
    @Test
    public void testSyncSongNewNoReferencesOtherEntities()
            throws NotLoggedInException
    {
        ArtistEntity art = new ArtistEntity();
        art.setName(ENTITY_NAME);
        art.setUser(PersistenceTestHelper.getTestUser());
        helper.persist(art);
        persistSong(ENTITY_NAME, null, DURATION, art);
        persistSong(ENTITY_NAME, null, Long.valueOf(DURATION + 30000), null);
        persistSong(ENTITY_NAME, null, null, null);
        persistSong(ENTITY_NAME + "_other", null, DURATION, null);
        SongData data = factory.createSongData();
        checkSyncSongAdded(data);
    }

    /**
     * Tests whether references to other entities are resolved when a new song
     * is added.
     */
    @Test
    public void testSyncSongNewWithReferences() throws NotLoggedInException
    {
        ArtistEntity art = new ArtistEntity();
        art.setName(ENTITY_NAME);
        art.setUser(PersistenceTestHelper.getTestUser());
        helper.persist(art);
        AlbumEntity album = persistAlbum(ENTITY_NAME, null, INCEPTION_YEAR);
        persistSong(ENTITY_NAME, null, DURATION, null);
        SongData data = factory.createSongData();
        data.setAlbumName(ENTITY_NAME);
        data.setArtistName(ENTITY_NAME);
        SongEntity song = checkSyncSongAdded(data);
        assertEquals("Wrong album ID", album.getId(), song.getAlbumID());
        assertEquals("Wrong artist ID", art.getId(), song.getArtistID());
    }

    /**
     * Tests a sync operation for a new song if there are references to other
     * entities which cannot be resolved.
     */
    @Test
    public void testSyncSongNewReferencesUnresolved()
            throws NotLoggedInException
    {
        persistAlbum(
                ENTITY_NAME,
                PersistenceTestHelper.getUser(PersistenceTestHelper.OTHER_USER),
                INCEPTION_YEAR);
        persistAlbum(ENTITY_NAME, null, INCEPTION_YEAR + 1);
        persistAlbum(ENTITY_NAME + "_other", null, INCEPTION_YEAR);
        SongData data = factory.createSongData();
        data.setAlbumName(ENTITY_NAME);
        data.setArtistName(ENTITY_NAME);
        SongEntity song = checkSyncSongAdded(data);
        assertNull("Got an album ID", song.getAlbumID());
        assertNull("Got an artist ID", song.getArtistID());
    }

    /**
     * Tests a sync operation if the song already exists.
     */
    @Test
    public void testSyncSongExisting() throws NotLoggedInException
    {
        persistSong(
                ENTITY_NAME,
                PersistenceTestHelper.getUser(PersistenceTestHelper.OTHER_USER),
                DURATION, null);
        SongEntity expSong = persistSong(ENTITY_NAME, null, DURATION, null);
        SongData data = factory.createSongData();
        initSongData(data);
        SyncResult<String> result = service.syncSong(data);
        assertFalse("Imported", result.imported());
        assertEquals("Wrong key", expSong.getId(),
                KeyFactory.stringToKey(result.getKey()));
        SongEntity song2 =
                helper.getEM().find(SongEntity.class, expSong.getId());
        assertEquals("Play count not increased", data.getPlayCount(),
                song2.getPlayCount());
    }

    /**
     * A test service implementation which provides some mocking facilities.
     */
    private static class MediaSyncServiceTestImpl extends MediaSyncServiceImpl
    {
        /** A mock for the OAUTH service. */
        private OAuthService mockOAuthService;

        /** Stores the user to be returned by authenticate(). */
        private User currentUser = PersistenceTestHelper.getTestUser();

        /**
         * Installs a mock OAUTH service.
         *
         * @return the mock service
         */
        public OAuthService installMockOAuthService()
        {
            mockOAuthService = EasyMock.createMock(OAuthService.class);
            return mockOAuthService;
        }

        /**
         * Returns the current user. This user is returned by
         * {@link #authenticateUser()}.
         *
         * @return the current user
         */
        public User getCurrentUser()
        {
            return currentUser;
        }

        /**
         * Allows setting the current user to be returned by
         * {@link #authenticateUser()}. Note that per default the test user is
         * set. This causes {@link #authenticateUser()} to be mocked; the user
         * is directly returned.
         *
         * @param currentUser the current user
         */
        public void setCurrentUser(User currentUser)
        {
            this.currentUser = currentUser;
        }

        /**
         * Either returns the mock current user or calls the super method.
         */
        @Override
        protected User authenticateUser() throws NotLoggedInException
        {
            return (getCurrentUser() != null) ? getCurrentUser() : super
                    .authenticateUser();
        }

        /**
         * Either returns the mock service or calls the super method.
         */
        @Override
        protected OAuthService fetchOAuthService()
        {
            return (mockOAuthService != null) ? mockOAuthService : super
                    .fetchOAuthService();
        }
    }
}
