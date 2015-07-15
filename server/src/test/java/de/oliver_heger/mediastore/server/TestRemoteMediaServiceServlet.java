package de.oliver_heger.mediastore.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;

import de.oliver_heger.mediastore.shared.persistence.PersistenceTestHelper;

/**
 * Test class for {@code RemoteMediaServiceServlet}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestRemoteMediaServiceServlet
{
    /** The persistence test helper. */
    private final PersistenceTestHelper helper = new PersistenceTestHelper(
            new LocalDatastoreServiceTestConfig());

    /** The service to be tested. */
    private RemoteMediaServiceServletTestImpl service;

    @Before
    public void setUp() throws Exception
    {
        helper.setUp();
        service = new RemoteMediaServiceServletTestImpl();
    }

    @After
    public void tearDown() throws Exception
    {
        helper.tearDown();
    }

    /**
     * Adds the test configuration so that no user is logged in.
     */
    private void notLoggedIn()
    {
        helper.getLocalServiceTestHelper().setEnvIsLoggedIn(false);
    }

    /**
     * Tests whether the user service can be obtained.
     */
    @Test
    public void testGetUserService()
    {
        assertNotNull("No user service", service.getUserService());
    }

    /**
     * Tests whether the current user can be queried.
     */
    @Test
    public void testGetCurrentUser()
    {
        assertEquals("Wrong current user", PersistenceTestHelper.getTestUser(),
                service.getCurrentUser());
    }

    /**
     * Tests the result of getCurrentUser() if no user is logged in.
     */
    @Test
    public void testGetCurrentUserNotLoggedIn()
    {
        notLoggedIn();
        assertNull("Got a current user", service.getCurrentUser());
    }

    /**
     * Tests whether an exception is thrown if no user is logged in and the flag
     * is set correspondingly.
     */
    @Test(expected = IllegalStateException.class)
    public void testGetCurrentUserNotLoggedInForce()
    {
        notLoggedIn();
        service.getCurrentUser(true);
    }

    /**
     * Tests checkUser() if the user is the expected one. We can only test that
     * no exception is thrown.
     */
    @Test
    public void testCheckUserMatch()
    {
        service.checkUser(PersistenceTestHelper.getTestUser());
    }

    /**
     * Tests checkUser() if the user does not match.
     */
    @Test(expected = IllegalStateException.class)
    public void testCheckUserNoMatch()
    {
        service.checkUser(PersistenceTestHelper
                .getUser(PersistenceTestHelper.OTHER_USER));
    }

    /**
     * Tests checkUser() if no user is logged in.
     */
    @Test(expected = IllegalStateException.class)
    public void testCheckUserNotLoggedIn()
    {
        notLoggedIn();
        service.checkUser(PersistenceTestHelper.getTestUser());
    }

    /**
     * A test implementation of the base media service servlet class.
     */
    private static class RemoteMediaServiceServletTestImpl extends
            RemoteMediaServiceServlet
    {
        /**
         * The serial version UID.
         */
        private static final long serialVersionUID = 20101202L;
    }
}
