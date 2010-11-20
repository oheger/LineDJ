package de.oliver_heger.mediastore.server.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.appengine.api.oauth.OAuthRequestException;
import com.google.appengine.api.oauth.OAuthService;
import com.google.appengine.api.users.User;
import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;

import de.oliver_heger.mediastore.server.NotLoggedInException;
import de.oliver_heger.mediastore.server.model.ArtistEntity;
import de.oliver_heger.mediastore.service.ArtistData;
import de.oliver_heger.mediastore.service.ObjectFactory;
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
