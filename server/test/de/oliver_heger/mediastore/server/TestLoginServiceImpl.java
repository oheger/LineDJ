package de.oliver_heger.mediastore.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;

import de.oliver_heger.mediastore.shared.LoginInfo;

/**
 * Test class for {@code LoginServiceImpl}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestLoginServiceImpl
{
    /** Constant for the request URL. */
    private static final String REQUEST_URL = "http://testapp.appspot.com";

    /** The mock user service. */
    private UserService userService;

    /** The service to be tested. */
    private LoginServiceImpl service;

    @Before
    public void setUp() throws Exception
    {
        userService = EasyMock.createMock(UserService.class);
        service = new LoginServiceTestImpl();
    }

    /**
     * Tests the login service if there is a logged in user.
     */
    @Test
    public void testGetLoginInfoLoggedIn()
    {
        final String logoutURL = REQUEST_URL + "/logout";
        User user = new User("harry@hirsch.de", "www.test.biz", "hhirsch");
        EasyMock.expect(userService.getCurrentUser()).andReturn(user);
        EasyMock.expect(userService.createLogoutURL(REQUEST_URL)).andReturn(logoutURL);
        EasyMock.replay(userService);
        LoginInfo info = service.getLoginInfo(REQUEST_URL);
        assertTrue("Not logged in", info.isLoggedIn());
        assertEquals("Wrong user name", user.getNickname(), info.getUserName());
        assertEquals("Wrong logout URL", logoutURL, info.getLogoutUrl());
        assertNull("Got login URL", info.getLoginUrl());
        EasyMock.verify(userService);
    }

    /**
     * Tests the login service if no user is logged in.
     */
    @Test
    public void testGetLoginInfoNotLoggedIn()
    {
        final String loginURL = REQUEST_URL + "/login";
        EasyMock.expect(userService.getCurrentUser()).andReturn(null);
        EasyMock.expect(userService.createLoginURL(REQUEST_URL)).andReturn(
                loginURL);
        EasyMock.replay(userService);
        LoginInfo info = service.getLoginInfo(REQUEST_URL);
        assertFalse("User logged in", info.isLoggedIn());
        assertNull("Got a user name", info.getUserName());
        assertNull("Got a logout URL", info.getLogoutUrl());
        assertEquals("Wrong login URL", loginURL, info.getLoginUrl());
        EasyMock.verify(userService);
    }

    /**
     * A test implementation of the login service that supports mocking the user
     * service.
     */
    @SuppressWarnings("serial")
    private class LoginServiceTestImpl extends LoginServiceImpl
    {
        /**
         * {@inheritDoc} Returns the mock user service.
         */
        @Override
        UserService getUserService()
        {
            return userService;
        }
    }
}
